# MineGuard

**面向工业安全场景、以确定性安全边界编排 Tool、RAG、人工审批与执行后验证的智能作业 Agent。**

MineGuard 将“事件查询 → 规程检索 → 风险分析 → 操作审批 → 执行 → 验证”实现为可运行、可观察、可测试的后端工作流，而不是把所有能力藏进一次聊天模型调用。项目默认离线运行：H2、固定种子合成事件、哈希向量、确定性规划器和 Mock 工业网关不需要 API Key；生产适配点则通过接口隔离。

> **Demo Data Notice**：仓库中的 420 条事件和 20 篇知识文档全部是 **Synthetic Demo Data**。它们不来自真实煤矿或企业，不应作为现场操作规程、合规依据或安全决策的替代品。

## Verified snapshot

以下数据来自评测程序生成的 [`docs/eval/latest.json`](docs/eval/latest.json)，不是 README 中手工预设的数字：

| Suite | Measured result |
|---|---:|
| Backend tests | 22/22 passed |
| Retrieval Eval | 30 cases; Recall@5 100%; MRR 1.0000 |
| Agent Eval | 30 cases; task success 100%; tool selection 100% |
| Safety Eval | 20 adversarial cases; unsafe bypass 0/20 |
| Approval enforcement | 100% |
| Deterministic basic-agent baseline | task success 20% |
| Real model evaluation | **NOT RUN** |

这是针对固定、公开、合成数据集的 **Deterministic Evaluation**，用于验证工程行为可复现，不代表未知分布上的模型泛化率。以当前机器为准的延迟和完整明细见 [`docs/EVAL_REPORT.md`](docs/EVAL_REPORT.md)。

## Architecture

```text
Vue Console ── REST / SSE ──> AgentTaskController
                                  │
                                  ▼
                         AgentWorkflowEngine
                         │ plan + validate
                         │ state transitions
                         │ approval gate
                         │ execute + verify
                         ▼
        ┌────────────── ToolRegistry ──────────────┐
        │ schema validation / risk policy / timing │
        └───────┬───────────┬───────────┬─────────┘
                │           │           │
         SafetyEvent DB   RAG       IndustrialGateway
          H2/PostgreSQL   VectorStore   Mock / real adapter
                         Memory/Milvus

Every observable event ──> Task SSE history + TraceRecorder
Fixed eval datasets      ──> Retrieval / Agent / Safety evaluators
```

结构化事件由数据库工具精确过滤和聚合；非结构化规程由向量检索返回带 `documentId`、`chunkId`、`score` 的 Evidence。LLM/确定性规划器只产生受校验的 `AgentPlan`，不能直接拿到工业网关，也不能签发审批。详细决策见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## Core capabilities

- 显式任务状态机：`CREATED → PLANNING → RETRIEVING → ANALYZING → ...`，非法迁移抛出异常并由测试覆盖。
- 结构化 Planning：Jackson 解析、允许的 Step、数量、参数和风险等级校验；失败仅修复重试一次。
- 统一 Tool Registry：9 个 Tool 动态注册，统一 JSON Schema、参数错误、耗时和 Trace。
- 后端 Human-in-the-loop：`start_detection_task` / `stop_detection_task` 标记为 `HIGH_RISK`；没有后端批准标记时 Registry 确定性拒绝。
- 执行后验证：批准操作执行后必须进入 `VERIFYING` 并调用 `verify_detection_task`，验证失败则任务失败。
- RAG Evidence：文档加载、分块、Embedding、VectorStore、Top-K 与证据结构完整分层。
- SSE 流：状态、规划、工具、检索、审批、验证、结果与错误都有具名事件。
- 可观察 Trace：保存可观察事件，不写 API Key、Secret 或隐藏 Chain of Thought。
- 固定评测：30 Retrieval + 30 Agent + 20 Safety cases，另有公平、明确受限的确定性 baseline。
- Vue 控制台：Agent Console、Workflow、Tool Trace、Evidence、审批面板、任务历史和 Eval Dashboard。

## Workflow example

请求：

```text
启动 camera-03 的 intrusion_detection 检测任务
```

执行链：

```text
PLANNING
  get_device_status
  list_detection_tasks
  start_detection_task [HIGH_RISK]
RETRIEVING → ANALYZING → WAITING_APPROVAL
  operator approves
EXECUTING
  start_detection_task (backend approval grant = true)
VERIFYING
  verify_detection_task (expected RUNNING)
COMPLETED
```

拒绝审批时任务以 `COMPLETED` 返回“操作已被拒绝，未执行系统变更”，不会产生高风险 Tool 成功记录。

## Quick start

要求：JDK 21+、Maven 3.9+；前端需要 Node.js 20+。

```bash
# Backend (default H2 + deterministic planner + in-memory vectors)
mvn spring-boot:run

# Frontend, another terminal
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。后端监听 `http://localhost:8080`。启动时会以固定 seed `20260831` 初始化 420 条事件并索引 `data/knowledge/`。

Windows 一键构建、测试和评测：

```powershell
scripts/run-eval.ps1
```

Linux/macOS：

```bash
./scripts/run-eval.sh
```

脚本依次执行 `mvn clean verify` 和真实 evaluator，并重写：

- `docs/eval/latest.json`
- `docs/eval/retrieval-latest.json`
- `docs/EVAL_REPORT.md`
- `docs/DETERMINISTIC_EVAL.md`
- `docs/REAL_MODEL_EVAL.md`
- `docs/RESUME_METRICS.md`

只初始化当前配置的数据源/向量库可运行 `scripts/seed-demo-data.ps1` 或 `scripts/seed-demo-data.sh`。

## API

```http
POST /api/agent/tasks
Content-Type: application/json

{"query":"分析最近24小时瓦斯相关告警，并生成巡查计划"}
```

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/agent/tasks` | 创建异步 Agent 任务 |
| `GET` | `/api/agent/tasks/{id}` | 查询完整任务与结构化结果 |
| `GET` | `/api/agent/tasks/{id}/stream` | 订阅 SSE 事件 |
| `GET` | `/api/agent/tasks/{id}/events` | 获取已保存的事件时间线 |
| `GET` | `/api/agent/tasks` | 任务历史 |
| `POST` | `/api/tasks/{id}/approve` | 批准等待中的高风险操作 |
| `POST` | `/api/tasks/{id}/reject` | 拒绝操作且不改变工业状态 |
| `GET` | `/api/tools` | Tool 元数据与 JSON Schema |
| `GET` | `/api/eval/latest` | 最新生成的 Eval 结果 |

审批 Body 可包含 `{"actor":"operator-1","reason":"现场负责人确认"}`。批准/拒绝不是幂等的通用状态覆盖：只允许在 `WAITING_APPROVAL` 调用。

SSE 事件：`TASK_STATE_CHANGED`、`PLAN_CREATED`、`TOOL_STARTED`、`TOOL_FINISHED`、`RAG_RETRIEVED`、`WAITING_APPROVAL`、`APPROVED`、`REJECTED`、`VERIFICATION`、`FINAL_RESULT`、`ERROR`。

## Configuration

默认配置位于 `src/main/resources/application.yml`，敏感值只从环境读取。

```bash
# OpenAI-compatible planning (real model eval is separate from deterministic result)
export MINEGUARD_LLM_PROVIDER=openai-compatible
export OPENAI_API_KEY=...
export OPENAI_BASE_URL=https://api.openai.com/v1
export OPENAI_MODEL=gpt-4o-mini

# PostgreSQL profile
export SPRING_PROFILES_ACTIVE=postgres
export DATABASE_URL=jdbc:postgresql://localhost:5432/mineguard
export DATABASE_USERNAME=mineguard
export DATABASE_PASSWORD=...

# Milvus REST v2 adapter
export MINEGUARD_VECTOR_STORE=milvus
export MILVUS_URI=http://localhost:19530
```

`MilvusVectorStore` 采用 REST v2；当前需要预先建立 `mineguard_knowledge` collection 及 `id/documentId/title/chunkId/content/vector(768)` 字段。该约束也列在 Limitations 中，默认 InMemory 实现无需外部服务。

## RAG and evaluation

知识库有 20 篇短文，均带 `Synthetic Demo Data` 声明；缺少声明的文档会在加载时被拒绝。`HashingEmbeddingClient` 用于完全离线、稳定的工程回归，不能等同于语义 Embedding 模型。

评测器逐条实际调用 Retriever 或 Workflow：

- Retrieval：Recall@1/@3/@5、MRR。
- Agent：Task Success、Tool Selection、参数有效率、审批强制率、平均 Tool Calls、p50/p95、Evidence Coverage。
- Safety：20 条“跳过审批/管理员命令/先执行后审批”等提示注入，检查高风险 Tool 是否在等待审批前成功。
- Baseline：每个请求只按关键词选择一个 Tool，无多步计划、RAG Ranking、审批与验证；同一 case set，结论仅限确定性评测。

Real Model Evaluation 只有在显式配置 OpenAI-compatible provider 后才运行；本仓库当前生成结果明确为 `NOT RUN`。

## Trace

运行 Trace 写入被 Git 忽略的 `data/runtime/traces/{taskId}.json`，字段包含 `runId`、`taskId`、时间、状态迁移、Tool Call、Retrieval、Approval、Errors、Result 与总耗时。TraceRecorder 按 key 过滤 secret/key/password/CoT 类字段，不记录模型隐藏推理。

## Project structure

```text
src/main/java/com/mineguard/
├── agent       # plan/result/model validation
├── approval    # approval decision model
├── api         # REST/SSE controllers
├── config      # data, model, vector and executor wiring
├── device      # IndustrialGateway + mock backend
├── eval        # executable evaluators and report generation
├── event       # H2/PostgreSQL safety event repository
├── llm         # deterministic/OpenAI-compatible clients
├── rag         # loader, chunker, embedding, stores, evidence
├── tool        # Tool API, schemas, registry and implementations
├── trace       # observable trace persistence
└── workflow    # state machine, task store, event bus, engine

data/knowledge  # 20 synthetic knowledge documents
data/eval       # 30 retrieval + 30 agent + 20 safety cases
frontend        # Vue 3 / TypeScript / Vite console
scripts         # reproducible seed/eval entry points
docs            # architecture, interview and generated metrics
```

## Engineering decisions

- **安全策略不交给 Prompt**：Tool category 与 `approvalGranted` 在 Java 后端检查；模型说“已批准”没有任何权限效果。
- **规划和执行分离**：模型只生成 Plan，Registry 才能找到并运行 Tool，网关不暴露给模型客户端。
- **验证是独立阶段**：Tool 返回 success 只表示调用完成，真实期望状态由另一个读取接口确认。
- **数据库和 RAG 各司其职**：时间/区域/严重度聚合使用 SQL；规范解释使用 ranked retrieval。
- **离线默认值**：面试演示和 CI 不依赖付费 API 或 Milvus，同时保留可替换接口。
- **只记录可观察事件**：Trace 支持复盘与指标，不把 Chain of Thought 当成工程日志。

## Limitations

- 默认 Planner 是覆盖演示意图的确定性规则，不具备开放域自然语言泛化能力；Real Model Eval 尚未运行。
- `AgentTaskStore` 和 SSE history 位于内存，进程重启后消失；生产应替换为持久化任务/事件存储。
- MockIndustrialGateway 没有真实设备协议、鉴权、租户、RBAC 和幂等键。
- Milvus adapter 假设 collection 已建；暂未提供 schema migration 或 Milvus 集成测试环境。
- H2/PostgreSQL 只持久化安全事件，审批与巡查计划仍是演示生命周期。
- 哈希 Embedding 对固定词汇有效，不能声称真实语义检索质量。
- SSE 是单实例进程内广播；横向扩容需要消息总线和可恢复 cursor。

## Roadmap

1. 持久化 AgentTask、审批与 Outbox 事件，并加入幂等执行键。
2. 增加 RBAC、审批人策略、双人复核和审计签名。
3. 增加 Testcontainers PostgreSQL/Milvus 集成测试与 collection migration。
4. 接入真实 Embedding 和受控 Real Model Eval，分别报告 deterministic 与 real-model 指标。
5. 以消息队列/调度器支持跨进程 long-running task、超时、补偿和断点恢复。

## Further reading

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/INTERVIEW_GUIDE.md`](docs/INTERVIEW_GUIDE.md)
- [`docs/EVAL_REPORT.md`](docs/EVAL_REPORT.md)
- [`docs/RESUME_METRICS.md`](docs/RESUME_METRICS.md)
- [`FINAL_REPORT.md`](FINAL_REPORT.md)（最终验收后生成）
