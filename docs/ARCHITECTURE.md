# MineGuard Architecture

## 1. System boundary

MineGuard 的核心不是“问知识库然后生成回答”，而是一个带安全边界的作业编排器。输入可能需要读结构化事件、检索规程、变更检测任务并确认结果；只有最后一项属于高风险工业状态变化。因此系统将认知层和执行层刻意分开：

```text
User query
  → AgentModelClient produces candidate AgentPlan
  → AgentPlanValidator checks structure, steps, schemas and risk
  → AgentWorkflowEngine owns lifecycle
  → ToolRegistry owns callable capabilities and policy
  → IndustrialGateway owns side effects
  → verification reads the resulting state independently
```

`AgentModelClient` 没有 `IndustrialGateway` 引用。它返回的 JSON 只是候选计划，不能构造一个可执行审批令牌，也不能直接调用 Tool。

## 2. Why this is not a RAG chatbot

普通 RAG Chatbot 的主要产物是一段基于文档的文本。MineGuard 的一次请求可能同时产生：

1. 数据库过滤与统计结果；
2. Top-K 安全知识 Evidence；
3. 一个待审批操作；
4. 工业网关执行记录；
5. 独立的状态验证；
6. 完整 SSE 与 Trace 时间线；
7. 结构化 `AgentResult`。

RAG 只解决“哪些合成规程片段相关”，不会回答“camera-17 当前是否在线”，也不会获得改变检测任务的能力。实时业务状态必须来自 Tool。

## 3. Workflow state machine

`AgentTaskState.canTransitionTo` 定义唯一合法边：

```text
CREATED → PLANNING → RETRIEVING → ANALYZING ─┬→ COMPLETED
                                               └→ WAITING_APPROVAL
                                                   ├→ COMPLETED (rejected)
                                                   └→ EXECUTING → VERIFYING → COMPLETED
non-terminal state → FAILED (only where declared)
```

`AgentTask.transitionTo` 在 synchronized 临界区校验迁移。Controller 不修改状态；所有迁移经 `AgentWorkflowEngine.transition`，同时发布 `TASK_STATE_CHANGED` 并写 Trace。终态不能重新启动。

状态机使异步任务的行为可解释：看到 `WAITING_APPROVAL` 就能断言执行尚未发生；看到 `COMPLETED` 且有 `executedOperations` 时，一定已经经过 `VERIFYING`。

## 4. Structured planning

模型输出映射为：

```json
{
  "intent": "start_detection",
  "riskLevel": "HIGH",
  "steps": [
    {"id":"step-1","type":"GET_DEVICE_STATUS","description":"...","args":{"deviceId":"camera-03"}},
    {"id":"step-2","type":"START_DETECTION_TASK","description":"...","args":{"cameraId":"camera-03","algorithm":"intrusion_detection"}}
  ]
}
```

`StructuredPlanner` 使用 Jackson 解析，再由 `AgentPlanValidator` 检查：意图与风险非空、1–10 个步骤、唯一 step id、枚举 Step Type、对应 Tool 存在、参数符合 ToolSchema，以及高风险 Tool 必须声明 HIGH。失败会把验证错误提供给模型做一次修复；第二次仍失败则抛 `PlanningException`，任务进入 `FAILED`。

离线 `DeterministicAgentModelClient` 让回归测试和评测无需模型 API。`OpenAiCompatibleAgentModelClient` 使用同一接口和验证链，因此接入真实模型不会改变后端安全边界。

## 5. Tool execution and registry

每个 Tool 暴露：

- `name/description/category`
- `ToolSchema`
- `execute(ToolContext, args)`

Spring 将 Tool 列表注入 `ToolRegistry`，Engine 只按 Step Type 映射到工具名并动态查询，不包含工具实现的 `if toolName.equals(...)`。Registry 负责：

1. 未知 Tool 拒绝；
2. required/type/enum/additionalProperties 校验；
3. `HIGH_RISK` 审批检查；
4. 捕获异常为 `TOOL_EXECUTION_ERROR`；
5. 记录 `elapsedMs` 和 Tool Trace。

这种分层允许添加新 Tool，而不把所有业务分支塞进 Workflow God Class。

## 6. Backend-enforced human approval

审批必须是能力控制，不是 Prompt 约定。`start_detection_task` 和 `stop_detection_task` 的 category 是 `HIGH_RISK`。Registry 的执行条件是：

```java
tool.category() == HIGH_RISK && !context.approvalGranted()
    → APPROVAL_REQUIRED
```

只有 `AgentWorkflowEngine.approve` 在确认任务当前为 `WAITING_APPROVAL` 后，才会调度 `runApproved`，后者构造 `approvalGranted=true` 的 `ToolContext`。用户文本、Plan args 和模型输出都没有设置这一字段的路径。直接调用 Registry、Prompt Injection 或伪造“管理员命令”仍被拒绝。

拒绝路径写入 `ApprovalDecision(REJECTED)` 并直接生成“未执行系统变更”的结果；它不会进入 `EXECUTING`。重复批准/拒绝因状态不再等待而返回冲突。

## 7. Why verification is separate

工业调用返回 HTTP/SDK success 不等于目标状态已经生效。启动/停止后，Engine 强制进入 `VERIFYING`，基于原步骤构造 `verify_detection_task`：

- start → expected `RUNNING`
- stop → expected `STOPPED`

Verifier 通过 `IndustrialGateway.verifyDetectionTask` 重新读取状态。失败会令整个任务 `FAILED`，而不是输出虚假的完成结论。真实网关可以把读取路由到独立查询端点或遥测数据源。

## 8. Structured data versus RAG

`SafetyEventRepository` 支持 area、eventType、start/end、severity 过滤和 SQL 聚合。这类精确条件、计数和时间窗口不应放进向量数据库：相似度搜索不能保证完整计数或范围正确。

知识文档适合 RAG，因为用户用自然语言询问处置依据。Pipeline 为：

```text
KnowledgeLoader (enforce Synthetic Demo Data notice)
  → paragraph-aware chunking with overlap
  → EmbeddingClient
  → VectorStore.replaceAll
  → cosine/Milvus top-K
  → Evidence(documentId, title, chunkId, score, content)
```

最终 `AgentResult` 保留 Evidence，而不是只把文本混入 Prompt 后丢失来源。

## 9. Industrial backend abstraction

`IndustrialGateway` 定义设备状态、检测任务列表、启动、停止和验证。`MockIndustrialGateway` 以并发 Map 提供 24 个 camera 和固定初始任务，用于演示与测试。真实系统替换步骤是实现相同接口，并在 Spring 配置中选择 Bean；Workflow 与 Tool Schema 不需要改变。

真实适配器还应增加身份映射、网络超时、重试策略、请求幂等键、审计签名和读写隔离。这些当前没有伪装成已实现功能。

## 10. Context management and long-running behavior

Task 是上下文边界：计划、Tool Calls、Evidence、Approval、Result 与 Error 都绑定 `taskId`，而不是累积在一个无限聊天消息列表中。异步固定线程池执行初始工作和批准后的续跑；`WAITING_APPROVAL` 时不占用工作线程。

这是可演示的 long-running lifecycle，但不是持久化工作流引擎：进程重启会丢任务，集群也没有分布式锁。生产版本应将 Task/Event 持久化，并用 Outbox/Queue 恢复续跑。

## 11. SSE and frontend projection

`TaskEventPublisher` 为每个 Task 维护单调 sequence、历史与 SSE subscribers。订阅时先 replay 当前进程内历史，再推送新事件。终态事件完成 emitter。前端只将同一 Task 的 Plan、Tool、Evidence 和 Approval 投影到界面，不在浏览器重新实现业务状态机。

## 12. Trace design

`TraceRecorder` 保存 `runId/taskId/userQuery/startedAt/finishedAt/durationMs/events/result`。事件只包含可观察工程事实：迁移、工具名和参数、检索 Evidence、审批决定、耗时、错误和结果。sanitizer 排除 key/secret/password/CoT 类字段；不记录 API Key 或隐藏推理。

文件通过临时文件 + move 写入 `data/runtime/traces`，该目录被 Git 忽略。生产环境应使用不可篡改审计存储和数据保留策略。

## 13. Evaluation architecture

- `RetrievalEvaluator` 实际调用 Retriever，计算 first relevant rank、Recall@K 和 MRR。
- `AgentEvaluator` 实际创建异步任务；审批 case 先检查高风险 Tool 未执行，再批准并等待终态。
- `SafetyEvaluator` 发送 20 条 adversarial prompts，仅检查达到等待态之前是否有高风险成功调用，然后拒绝以结束任务。
- `BasicAgentBaselineEvaluator` 公开定义为“单关键词、单工具”基线，不使用模型，也不假装计算 token。
- `TestReportReader` 解析 Surefire XML；`EvaluationOrchestrator` 将全部实际值写入 JSON/Markdown。

评测 Case 本身与知识文档都是仓库中的可检查输入，便于复现和发现过拟合。固定 Case 上的 100% 只证明当前规则满足这些明确契约。

## 14. Failure semantics

- 无效 Plan：一次修复后仍无效 → `FAILED`。
- Tool 参数错误：Registry 返回 `INVALID_ARGUMENTS`；Workflow → `FAILED`。
- 工业网关异常：转为 `TOOL_EXECUTION_ERROR`；Workflow → `FAILED`。
- 验证不一致：`VERIFICATION_FAILED` → `FAILED`。
- 人工拒绝：没有系统异常，任务 `COMPLETED`，Result 明确未执行。
- 非法状态 API：HTTP 409；不存在 Task：HTTP 404；无效请求：HTTP 400。

## 15. Security and production gaps

当前最重要的已实现安全属性是“自然语言不能绕过高风险审批”。但完整生产安全还需要认证、RBAC/ABAC、审批人资格、双人规则、CSRF、租户边界、速率限制、幂等、签名审计、加密和工业网络隔离。README 的 Limitations 对这些边界做了显式声明。
