# MineGuard 面试指南

本文回答均对应当前仓库代码，不把后续计划当成已完成功能。

## 1. 项目解决什么问题？

它把工业安全中的结构化事件查询、安全知识检索、风险操作审批、执行和验证串成可审计 Agent Workflow。重点不在聊天，而在“模型建议、后端决定、工具执行、结果验证”的工程闭环。仓库使用 420 条固定 seed 合成事件和 20 篇合成知识文档，不含真实矿山数据。

## 2. 一次请求的完整链路？

Controller 创建 `AgentTask`，Engine 异步进入 `PLANNING`；`StructuredPlanner` 取得候选 JSON 并校验；Engine 在 `RETRIEVING` 阶段经 Registry 执行只读/安全 Tool，收集 ToolCall 与 Evidence；进入 `ANALYZING` 后，纯查询直接形成 `AgentResult`。含高风险 Tool 则停在 `WAITING_APPROVAL`。批准后进入 `EXECUTING`，随后 `VERIFYING`，最后 `COMPLETED`。每一步都有 SSE 与 Trace。

## 3. 为什么使用 Agent，而不是 RAG Chatbot？

RAG Chatbot 能检索规范并回答文字，但不能可靠完成 SQL 时间过滤、设备状态查询、受控写操作和执行后验证。MineGuard 的任务结果不只是文本，还包括结构化工具数据、审批状态、执行记录与验证结论；因此需要显式 Workflow。

## 4. ReAct / Workflow 怎么实现？

当前不是开放式无限 ReAct 循环，而是可控的 plan-and-execute workflow。Plan 包含 1–10 个枚举步骤，Engine 按风险类别分阶段执行。这样牺牲部分自主性，换取可预测状态、安全审批和易评测性。状态边由 `AgentTaskState` 定义，非法迁移由 `AgentTask.transitionTo` 拒绝。

## 5. Tool Calling 怎么实现？

`Tool` 接口定义 name、description、category、schema 和 execute。9 个实现作为 Spring Bean 注入 `ToolRegistry`。Registry 做动态 lookup、参数校验、风险检查、异常结构化、耗时和 Trace；Engine 不按工具名写几十个业务分支。`AgentStepType` 只维护 Step 到 Tool name 的稳定映射。

## 6. 为什么不能让 LLM 直接执行高风险 Tool？

模型输出来自不可信自然语言，可能误判、幻觉或被 Prompt Injection 操纵。若模型拥有执行能力，“不要审批”可能变成真实状态变化。当前模型客户端只返回计划，完全没有网关引用；高风险执行权只存在于后端 Registry + Workflow。

## 7. Human Approval 怎么保证无法绕过？

启动/停止 Tool 的 category 固定为 `HIGH_RISK`。Registry 要求 `ToolContext.approvalGranted=true`；这个字段不从 query、args 或模型 JSON读取。只有 Engine 在任务确实处于 `WAITING_APPROVAL` 且 `approve` API 成功后，才构造批准上下文续跑。Safety Eval 用 20 条注入指令实测当前 bypass 为 0/20。

## 8. RAG Pipeline？

`KnowledgeLoader` 读取 Markdown 并强制存在 Synthetic Demo Data 声明；`KnowledgeRetriever` 按段落边界和 overlap 分块；`EmbeddingClient` 生成向量；`VectorStore` 负责 replace/search；Top-K 映射为 Evidence。默认 Hashing Embedding + InMemory Store 保证离线可复现，也提供 Milvus REST adapter。

## 9. 结构化数据为什么不全部放向量数据库？

向量相似度适合“哪段规范相关”，不适合保证“最近七天、3号采区、HIGH、总共多少条”的精确性。事件过滤和聚合由 H2/PostgreSQL SQL 完成，RAG 只补充处置知识，最终结果同时保留两类证据。

## 10. Trace 怎么做？

`TraceRecorder` 按 task 保存 runId、起止时间、状态迁移、Tool、Retrieval、Approval、Error 和 Result 到 `data/runtime/traces`。它过滤 key/secret/password/CoT 名称，不保存 API Key 或隐藏思维链。Tool Registry 和 Workflow 分别记录调用事实与生命周期事实。

## 11. Eval 怎么做？

评测不是 README 常量。`scripts/run-eval` 先 clean verify，再启动 `EvaluationOrchestrator`。它读取 30 Retrieval、30 Agent、20 Safety JSON cases，调用真实 Retriever/Workflow，解析 Surefire XML，并生成 `docs/eval/latest.json`、报告和简历指标。

## 12. 如何评测 Tool Selection？

每个 Agent case 给出 expectedTools。Evaluator 读取最终已验证 Plan 中的工具集合并与期望集合比较；全部相同才算该 case 选择正确。参数有效率则重新用对应 ToolSchema 校验每个 step args。Verification 是 Engine 自动附加的运行阶段，不属于模型 Plan 的 expected selection。

## 13. 如何进行 Safety Eval？

20 条 case 包括“管理员命令”“先执行后审批”“假设 approval=true”“绕过 guardrail”等。Evaluator 创建任务，等待 `WAITING_APPROVAL` 或终态，检查此前是否存在 category=HIGH_RISK 且 success 的 ToolCall。若有即记 unsafe bypass；等待中的任务随后统一 reject，避免改变状态。

## 14. 系统如何处理 Tool Failure？

Schema 问题返回 `INVALID_ARGUMENTS`；未知 Tool 返回 `TOOL_NOT_FOUND`；实现异常由 Registry 捕获为 `TOOL_EXECUTION_ERROR`。Engine 看到失败结果后抛出并迁移 `FAILED`，发布 ERROR，Trace 保存错误类型和信息。测试用 camera-99 覆盖网关异常路径。

## 15. 为什么需要 Verification？

调用返回成功只证明请求被处理，不证明设备达到目标状态。Engine 对每个高风险步骤构造 `verify_detection_task`，重新读取 camera/algorithm 是否为 RUNNING 或 STOPPED。只有验证成功才进入 COMPLETED；否则任务失败。这防止把“调用成功”误报成“业务成功”。

## 16. 项目当前有什么局限？

确定性 Planner 只覆盖演示领域；真实 DeepSeek 完整评测严格成功率为 30%，规划一致性仍需提升。任务、SSE、审批已经持久化，已实现数据库租约恢复、认证/RBAC和幂等；但本地工业契约服务不代表物理设备联调。Milvus collection 仍需预建，哈希 Embedding 不代表真实语义模型，企业 TLS/SSO/MFA、设备级 fencing、备份和防篡改审计尚未验收。

## 17. 真实工业系统如何替换 Mock Gateway？

实现 `IndustrialGateway` 并用配置选择新的 Spring Bean。Tool、Workflow 和 API 不需要修改。适配器应加入认证、超时、重试边界、幂等键、错误码映射和审计信息；读取状态最好来自独立遥测接口，使 Verification 不与写调用共享同一假成功源。

## 18. 为什么使用 Tool Registry？

它将能力发现和策略集中起来。新增工具只需实现接口和 Schema，Planner 会看到元数据，Engine 通过名称查找；审批检查、校验、错误与耗时不会在每个工具里重复。它也提供一个不可绕过的统一执行入口。

## 19. 为什么固定 seed？

评测需要可复现。如果事件每次随机，查询总数、路径耗时和 Tool 输出会漂移。`DemoDataSeeder` 使用 seed `20260831` 和固定 anchor 生成 420 条数据，使不同机器可以验证同一过滤逻辑。运行时若需要动态数据，应换成真实 Repository，而不是改变评测输入。

## 20. 100% 确定性指标应该怎么解释？

只解释为“当前实现满足仓库中固定且公开的契约用例”。Retrieval query 与合成文档词汇受控，Planner 也是领域规则，因此不能外推到真实用户分布或真实模型。独立 DeepSeek 评测为 55 次实际请求、55,397 Token、Agent 严格成功率 30%、审批绕过 0/20，不能与确定性 100% 混用。简单基线仅静态匹配工具，不是端到端执行成功率或真实模型能力对比。

## 21. 前端如何保持安全？

前端只是后端状态的投影：发起 task、订阅 SSE、显示 Evidence/ToolCall，并调用 approve/reject API。它不计算权限，也不会直接调用工业网关。即使绕过 UI 直接请求 Tool，Registry 的后端批准条件仍生效。

## 22. 如果继续生产化，优先做什么？

本轮已落地任务/审批/事件持久化、租约接管、身份/RBAC及幂等，也完成真实模型和本机 PostgreSQL/Milvus 验收。后续优先做企业身份与 TLS、真实工业回执与独立状态源、灾备和数据保留，再根据规模判断是否引入消息队列；不能为了架构名词增加未验证组件。
