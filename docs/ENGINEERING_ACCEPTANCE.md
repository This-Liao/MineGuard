# 当前工程验收

验收日期：2026-09-13。本次在既有 LangChain4j、Lucene BM25、Milvus 与 RRF 链路上新增扩展检索集、独立扰动鲁棒性评测和 LangChain4j 专项回归入口。2026-09-12 的 132 项测试 / 78.41% 仍保留其日期，不再作为当前数字展示。

## 干净构建结果

| 验收 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -q clean verify` | Surefire **140/140**，0 失败、0 异常、0 跳过 |
| 外部服务与分布式恢复 | `MINEGUARD_RUN_EXTERNAL_IT=true` 后执行 `mvn -q -Pexternal-it verify` | Failsafe **3/3**，0 失败、0 异常、0 跳过 |
| 前端交互 | `npm test` | **28/28** |
| 前端构建 | `npm run build` | TypeScript 与 Vite 构建通过 |
| 向量服务契约 | `python -m unittest discover -s scripts/embedding -p 'test_*.py' -v` | **4/4**，使用测试替身 |
| JaCoCo 指令 | `verify` 阶段生成并执行 ≥ 70% 门禁 | **73.06%**，18,206 / 24,919；6,713 条未覆盖 |

本地使用 Windows、JDK 22.0.2（编译目标 Java 21）、Maven 3.9.9。CI 使用 Ubuntu 24.04、Temurin 21、Node 22，两者的覆盖率不要求字节级相同。未排除业务类、未关闭失败测试、未降低 70% 门禁。

摘要与分母见 [工程快照 JSON](eval/engineering-2026-09-13.json)。LangChain4j [专项回归报告](LANGCHAIN4J_EVAL.md) 与 v3 混合检索 [完整排名报告](eval/hybrid-retrieval-v3/report.json) 独立保存，不计入 JUnit 通过数。完整 XML 与 HTML 由 CI artifact 保存 14 天；本地 `target/` 会被下次 clean 清理。

## 拆分后的职责与安全回归

`AgentWorkflowEngine` 从约 16 KB 缩到约 9 KB，保留状态机和应用入口；新增 `WorkflowScheduler`、`StepExecutor`、`ApprovalGuard`、`RecoveryCoordinator`。没有改动冻结的 Planning v2、模型工具契约、工具实现和评分器。

验证了租约竞争、旧 fence 拒绝提交、租约过期接管、等待审批持久化、跨节点 SSE、审批后篡改、审批过期/审批人停用、回执不符/不可用，以及已记录失败的崩溃恢复。新增调度器单测覆盖容量限制、提交拒绝释放、执行异常清理和心跳临时失败。

PostgreSQL / Milvus 使用已启动的独立 Docker 测试服务；跨进程测试真实启动独立 JVM 并强制结束节点，模型采用本地桩。仅清理本次随机 schema 与 collection，开发机现有数据库卷和工作台服务未被删除或重启。

新增 6 项后端测试覆盖 AI Services 的 OpenAI-compatible 请求与类型化解析、调用额度与失败不重试、Lucene 中文/设备号/算法 ID 召回、索引替换安全以及向量/BM25/RRF 三路排名。`WorkflowScheduler` 只增加 bean 限定符以区分工作流线程池和检索线程池，调度行为未改变。

本轮新增 4 项后端测试，校验 80 条扩展集的类别分母、64 条鲁棒性集的 16 个四变体意图组、组内相关文档标注一致性、RRF 挽救/损伤配对统计，以及最差变体召回和排名波动计算。评测数据位于独立目录，不改变应用默认加载的 20 篇知识文档。

本轮再新增 4 项后端测试，校验 LangChain4j 专项回归冻结清单、隔离依赖覆盖、专项评分器，以及 HTTP/类型化解析/修复/usage/延迟的分层统计。真实 DeepSeek 批次为 24 条任务、30 次请求和 65,027 Token，独立于 JUnit 数量，详见 [专项报告](LANGCHAIN4J_EVAL.md)。

## 效果指标不混入测试通过数

真实 DeepSeek 留出为 **21/24**；v3 混合检索包含 **80 条主对照查询**，独立鲁棒性套件包含 **16 个意图的 64 条扰动查询**。它们不是 JUnit/Vitest 测试分母。分别见 [Agent 留出](HOLDOUT_EVAL.md) 和 [混合检索](SEMANTIC_RETRIEVAL.md)。v3 中 RRF 相比向量单路的 Recall@5 在主对照提高 5.00 个百分点，在扰动集提高 10.16 个百分点；逐题三路结果与退化样本均完整保留。

GitHub 工作流与运行入口见 [CI 说明](CI.md)。后续提交使用描述具体变更的 `fix:`、`feat:`、`test:`、`refactor:`、`docs:`；不改写已有 Git 历史。
