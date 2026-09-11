# 当前工程验收

验收日期：2026-09-12。本次覆盖 LangChain4j AI Services、Lucene BM25、Milvus 并行召回、RRF 融合与三路评测。2026-09-01 的 126 项测试 / 82.84% 仍保留其日期，不再作为当前数字展示。

## 干净构建结果

| 验收 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -q -Pexternal-it clean verify` | Surefire **132/132**，0 失败、0 异常、0 跳过 |
| 外部服务与分布式恢复 | 同上，`MINEGUARD_RUN_EXTERNAL_IT=true` | Failsafe **3/3**，0 失败、0 异常、0 跳过 |
| 前端交互 | `npm test` | **28/28** |
| 前端构建 | `npm run build` | TypeScript 与 Vite 构建通过 |
| 向量服务契约 | `python -m unittest discover -s scripts/embedding -p 'test_*.py' -v` | **4/4**，使用测试替身 |
| JaCoCo 指令 | `verify` 阶段生成并执行 ≥ 70% 门禁 | **78.41%**，16,669 / 21,260；4,591 条未覆盖 |

本地使用 Windows、JDK 22.0.2（编译目标 Java 21）、Maven 3.9.9。CI 使用 Ubuntu 24.04、Temurin 21、Node 22，两者的覆盖率不要求字节级相同。未排除业务类、未关闭失败测试、未降低 70% 门禁。

摘要与分母见 [工程快照 JSON](eval/engineering-2026-09-12.json)。LangChain4j [真实调用报告](eval/langchain4j-smoke-2026-09-12.json) 与混合检索 [完整排名报告](eval/hybrid-retrieval-v2/report.json) 独立保存，不计入 JUnit 通过数。完整 XML 与 HTML 由 CI artifact 保存 14 天；本地 `target/` 会被下次 clean 清理。

## 拆分后的职责与安全回归

`AgentWorkflowEngine` 从约 16 KB 缩到约 9 KB，保留状态机和应用入口；新增 `WorkflowScheduler`、`StepExecutor`、`ApprovalGuard`、`RecoveryCoordinator`。没有改动冻结的 Planning v2、模型工具契约、工具实现和评分器。

验证了租约竞争、旧 fence 拒绝提交、租约过期接管、等待审批持久化、跨节点 SSE、审批后篡改、审批过期/审批人停用、回执不符/不可用，以及已记录失败的崩溃恢复。新增调度器单测覆盖容量限制、提交拒绝释放、执行异常清理和心跳临时失败。

PostgreSQL / Milvus 使用已启动的独立 Docker 测试服务；跨进程测试真实启动独立 JVM 并强制结束节点，模型采用本地桩。仅清理本次随机 schema 与 collection，开发机现有数据库卷和工作台服务未被删除或重启。

新增 6 项后端测试覆盖 AI Services 的 OpenAI-compatible 请求与类型化解析、调用额度与失败不重试、Lucene 中文/设备号/算法 ID 召回、索引替换安全以及向量/BM25/RRF 三路排名。`WorkflowScheduler` 只增加 bean 限定符以区分工作流线程池和检索线程池，调度行为未改变。

## 效果指标不混入测试通过数

真实 DeepSeek 留出为 **21/24**，混合检索回归为 **30 条查询**；它们不是 JUnit/Vitest 测试分母。分别见 [Agent 留出](HOLDOUT_EVAL.md) 和 [混合检索](SEMANTIC_RETRIEVAL.md)。三路结果完整保留，RRF 未超过向量单路。

GitHub 工作流与运行入口见 [CI 说明](CI.md)。后续提交使用描述具体变更的 `fix:`、`feat:`、`test:`、`refactor:`、`docs:`；不改写已有 Git 历史。
