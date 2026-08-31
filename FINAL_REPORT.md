# MineGuard 第一阶段验收报告（历史快照）

以下保留最初阶段的结果与当时局限，不代表当前代码状态。2026-08-31 后续已实现任务/SSE 持久化、分布式恢复、认证/RBAC与工业 HTTP 契约适配，并实际运行 DeepSeek。最新结果见 [当前验收](docs/DURABILITY_SECURITY_ACCEPTANCE.md) 和 [DeepSeek 实测](docs/DEEPSEEK_ACCEPTANCE.md)。

本次记录对应 2026-08-31（Asia/Shanghai）的阶段验收。机器可读结果位于 `docs/eval/latest.json`，生成时间为 `2026-08-30T18:33:25.806636Z`。本次中文化保留原有指标与时间，不代表重新运行了评测。

## 已实现功能

- Java 21 / Spring Boot 3.3 后端，默认 H2，并提供 PostgreSQL 配置。
- 固定种子生成 420 条合成安全事件，覆盖 5 个区域和 7 种事件类型。
- 9 个带参数规范的工具，涵盖事件、统计、设备、检测任务、知识检索、巡查草案与执行验证。
- 动态工具注册表，统一参数校验、结构化错误、耗时、追踪与高风险审批检查。
- 显式任务状态机，拒绝非法状态迁移。
- 确定性离线与 OpenAI-compatible 规划客户端。
- 结构化计划解析、参数与风险检查，以及一次修复重试。
- 20 篇合成安全知识文档，支持加载、分块、向量化、Top-K 检索和证据。
- VectorStore 接口及内存、Milvus REST v2 实现。
- 后端批准/拒绝流程；被拒绝的高风险操作不执行。
- 检测任务启动/停止后必须独立验证。
- REST 接口、具名 SSE 事件、进程内事件历史与结构化 AgentResult。
- JSON 追踪包含状态迁移、工具、检索、审批、异常、耗时和结果。
- 知识检索、Agent、安全评测和简化基线静态评分，自动生成 JSON/Markdown 报告。
- Vue 3 / TypeScript / Vite 控制台，展示工作流、工具时间线、证据、审批、历史与评测。
- Windows 与 Unix 系统的数据初始化和评测脚本。
- README、架构设计、面试指南、评测报告与实测简历指标。

## 系统架构

AgentModelClient 生成候选计划，由 StructuredPlanner 和 AgentPlanValidator 解析校验；AgentWorkflowEngine 管理任务生命周期，并通过 ToolRegistry 调用工具。高风险调用的批准标记由后端审批续跑路径构造，用户文本与模型参数不能直接设置。IndustrialGateway 封装工业状态变更，之后通过独立读取验证结果。精确事件查询使用 SQL，非结构化安全知识使用 RAG 排序检索。

## 测试结果

执行命令：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-eval.ps1`

- Maven 干净构建、编译、打包、验证：通过
- JUnit 测试总数：22
- 通过：22
- 失败：0
- 异常：0
- 跳过：0
- Spring Boot 可执行 JAR：构建成功
- JaCoCo 报告：已生成；覆盖率低于要求中的 70%，未作为简历指标。
- 前端 `npm run build`：通过
- 前端 `npm audit --omit=dev`：验收时未发现已知漏洞

## 评测结果

以下指标来自上述阶段生成的 JSON 与评测报告。

### 确定性知识检索：30 条用例

- Recall@1：100.00%
- Recall@3：100.00%
- Recall@5：100.00%
- MRR：1.0000

### 确定性 Agent：30 条用例

- 任务成功率：100.00%
- 工具选择准确率：100.00%
- 工具参数有效率：100.00%
- 审批强制率：100.00%
- RAG 证据覆盖率：100.00%
- 平均工具调用次数：2.37
- p50 任务耗时：3 ms
- p95 任务耗时：18 ms

### 安全评测：20 条对抗用例

- 审批强制生效：20/20
- 高风险操作审批绕过：0/20

### 简化基线：30 条用例

- 静态任务匹配评分：20.00%
- 静态工具匹配准确率：20.00%

注意：当前基线只按关键词选取一个工具并比较预期，不实际执行任务。原始 JSON 中的调用数 1.00 是代码预设值，不应作为实测调用次数；上述评分也不能称为端到端任务执行成功率。

### 真实模型评测

未运行（NOT RUN）。此阶段记录不报告真实模型结果或 Token 指标。后续已补齐 DeepSeek Token 回执、超时、进程内次数上限和独立评测入口，并完成离线假服务测试；尚未执行付费调用，也未实现货币预算硬限额。接入说明见 `docs/DEEPSEEK_SETUP.md`。

## 已知局限

- 确定性满分只适用于固定、公开的合成用例，不能代表真实模型或生产环境的泛化能力。
- 默认哈希向量用于离线回归，不代表生产语义检索质量。
- 任务和 SSE 历史保存在进程内，尚无重启恢复或多实例调度。
- 模拟工业网关尚无真实协议、认证、RBAC 与幂等键。
- Milvus 需要预先创建集合，本轮未连接外部 Milvus 完成集成验收。
- 提供了 PostgreSQL 配置，但本轮验收使用 H2。
- 审批身份只是演示元数据，尚未实现经过认证的审批人策略。
- 真实模型评测、Token 用量和同条件模型对比尚未完成。

## 可用于简历的实测表述

1. 构建 30 条 Agent Eval 与 20 条 Safety Eval，离线确定性任务成功率 100.00%，高风险操作审批绕过 0/20。
2. 构建合成工业安全知识 RAG，在 30 条固定 Retrieval Cases 上实测 Recall@5 100.00%、MRR 1.0000。
3. 以状态机编排 Tool、RAG、人工审批与执行后验证，并通过可观察 Trace 统计工具选择准确率 100.00% 和 p50/p95 延迟 3/18 ms。

使用时必须保留“确定性、固定用例、合成数据”的限定。

## 复现命令

```powershell
# 后端干净构建、全部测试与确定性评测
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-eval.ps1

# 前端生产构建
Set-Location .\frontend
npm install
npm run build
npm audit --omit=dev
```

```bash
# Linux/macOS 对应命令
./scripts/run-eval.sh
cd frontend && npm install && npm run build
```

启动应用：

```powershell
mvn spring-boot:run
# 在另一个终端启动前端
Set-Location frontend
npm run dev
```
