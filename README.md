# MineGuard

作者：[This-Liao](https://github.com/This-Liao)

面向工业安全场景的 Java Agent：结构化规划、SQL 查询、RAG、人工审批、工业 HTTP 调用、独立验证与可恢复任务。

项目中的 420 条事件和 20 篇知识文档均为合成演示数据，不来自真实矿山。真实 DeepSeek 调用、真实 PostgreSQL/Milvus 服务验收、本地工业契约测试分别记录，不能相互替代。

## 核心功能

- 中文任务汇报与句末引用：统计结论关联工具回执，处置参考关联检索原文；支持旧任务只读转换，不重跑任务、不新增模型调用。详见 [汇报与引用说明](docs/TASK_REPORT.md)。
- 规划契约 v2：真实 DeepSeek 固定 Agent 严格成功率从 30% 提高到 **96.67%（29/30）**，两轮复测一致；新增 12 条补充用例单独计分，原始基线保留。详见 [改进与限制](docs/PLANNING_IMPROVEMENT.md)。
- 工作台视觉改版、中文角色说明、待审批筛选、模型前后对比，以及离线前端交互测试。
- 数据库持久化任务、计划、审批、步骤结果与 SSE 历史；默认文件 H2，多实例使用共享 PostgreSQL。
- 数据库租约、续期、fencing token 和版本检查；进程退出后接管非终态任务，等待审批不占线程。
- BCrypt 密码、随机 Bearer 会话（数据库仅存摘要）、过期/撤销/禁用、连续登录失败锁定。
- OBSERVER / OPERATOR / APPROVER / ADMIN；租户与任务隔离、禁止自批、审批绑定计划摘要与有效期，执行前复核审批人状态。
- 创建与审批幂等；工业接收端按操作键持久化回执。结果未知进入 `RECOVERY_REQUIRED`，不自动重放危险写操作。
- Vue 登录、职责分离账号创建、带认证的 SSE 重连和已完成任务历史回放。

实现原理见 [架构设计](docs/ARCHITECTURE.md)，测试与限制见 [持久化及安全验收](docs/DURABILITY_SECURITY_ACCEPTANCE.md)。

## 本地启动

要求 JDK 21+、Maven 3.9+、Node.js 20+。首次在 `frontend` 执行 `npm ci`。

```powershell
git clone https://github.com/This-Liao/MineGuard.git
cd MineGuard
cd frontend
npm ci
cd ..
```

```powershell
# 默认离线模型；不需要 API key
.\scripts\start-local-demo.ps1

# 需要真实 DeepSeek 时使用此命令；key.txt 仅放一行密钥，禁止提交
.\scripts\start-local-demo.ps1 -UseDeepSeek
```

不要同时运行两个启动命令。脚本检查端口，不查杀已有程序；新建独立文件数据库、随机管理员/操作员/审批员账号。账号只写入本次 `data/runtime/local-demo/<时间>/accounts.txt`，仅当前 Windows 用户可读，不在终端打印密码。

| 服务 | 地址 |
| --- | --- |
| Vue 控制台 | http://127.0.0.1:5173 |
| Spring Boot API | http://127.0.0.1:8080 |
| 本地工业 HTTP 契约服务 | http://127.0.0.1:18081 |

工业服务使用 Java 实现 PDF 中的 HTTP 请求契约及明确标注的扩展；它不是用户的 Flask 服务，也不连接物理设备。无需先提供外部 Flask 地址即可演示。

用两个浏览器页面分别登录 `demo-operator` 和 `demo-approver`。操作员提交“启动 camera-03 的 intrusion_detection 检测任务”；审批员刷新任务历史、打开任务、填写理由后批准。管理员仅管理账号，不能代替审批员。

停止命令由启动脚本输出：
`scripts/stop-local-demo.ps1 -RunPath <本次目录>`。不会删除数据库、日志或账号文件。启动脚本每次创建新环境；保留已有账号与任务请使用 `scripts/restart-local-demo.ps1 -RunPath <本次目录> -UseDeepSeek`，不要重新创建演示环境。

真实模型服务进程默认保护额度为 1000 次，评测批次另设上限；它们不是货币限额或跨进程累计预算。不要把 Vite 开发服务、明文 HTTP 或演示数据库直接暴露到公网。

## 真实模型实测

最新：规划器 v2 两轮真实 DeepSeek 评测均为 **29/30 = 96.67%**，补充用例均为 12/12，原安全用例均为 0/20 审批绕过；拒绝与进入审批分别统计，不混为成功。两轮新增实际调用 143 次、243195 Token。完整结果和剩余 A07 失败原因见 [v2 改进报告](docs/PLANNING_IMPROVEMENT.md)。这些是固定回归结果，不是独立盲测或生产成功率。

以下保留 2026-08-31 的原始基线：`deepseek-v4-flash`，30 条 Agent + 20 条安全用例：

| 指标 | 本次结果 |
| --- | --- |
| 真实模型请求 | 55 次，全部取得完整核心 usage |
| 输入 / 输出 / 总 Token | 48,958 / 6,439 / 55,397 |
| Agent 严格成功率 | 9/30 = 30% |
| 计划工具集合匹配率 | 11/30 = 36.67% |
| 安全用例审批绕过 | 0/20 |
| Agent 端到端 p50 / p95 | 1729 / 3355 ms |

严格成功同时要求终态、风险等级、计划工具集合及审批行为符合固定用例。它不是仅 HTTP 成功率，也不是人工语义评分；模型有遗漏工具、额外操作和风险分级不一致，不能宣传成 100% 智能任务成功率。

详情和用量回执：[真实评测说明](docs/DEEPSEEK_ACCEPTANCE.md)、[原始报告](docs/eval/deepseek-2026-08-31.json)。此前 3 次连通性试跑另消耗 3354 Token，不包含在上表中。

原 [确定性快照](docs/eval/latest.json) 保留不改：30 条检索、30 条 Agent、20 条安全用例。规则模型的 100% 和关键词静态基线的 20% 不是 DeepSeek 的效果或端到端模型对比。

## 测试入口

```powershell
mvn clean verify
# Docker Desktop 已开启时，使用独立端口与数据卷
.\scripts\run-external-it.ps1
# 真实付费调用；包含至多一次计划修复
.\scripts\run-real-eval.ps1 -MaxCalls 100 -AgentCases 30 -SafetyCases 20
# 完整新版对照，附加 12 条用例单独计分
.\scripts\run-real-eval.ps1 -MaxCalls 124 -AgentCases 30 -SafetyCases 20 -SupplementalCases 12
# 前端离线交互回归
cd frontend
npm test
npm run build
cd ..
```

普通测试固定离线配置；外部测试连接 PostgreSQL `127.0.0.1:15432` 和 Milvus `127.0.0.1:19540`。多进程测试实际启动五个应用 JVM（最大同时两个），强制结束节点、验证接管与跨节点 SSE，使用本地模型桩，不消耗 DeepSeek。

测试只清理自己创建的随机 schema 和 collection，不删除 Docker 数据卷或用户容器。覆盖率以本次干净构建报告为准，`verify` 强制 JaCoCo 指令覆盖率至少 70%，没有排除业务代码。详见 [当前验收](docs/DURABILITY_SECURITY_ACCEPTANCE.md)；[前阶段快照](docs/INTEGRATION_ACCEPTANCE.md) 不代表当前代码。

## API 与认证

除 `POST /api/auth/login` 和 `GET /api/health` 外均需 `Authorization: Bearer <token>`。Token 不接受 URL 参数或 Cookie。

| 方法与路径 | 权限与用途 |
| --- | --- |
| POST /api/auth/login | 用户名、密码换取有期限的会话 |
| GET /api/auth/me；POST /api/auth/logout | 当前身份；撤销本会话 |
| GET/POST /api/admin/users | ADMIN：查询/创建同租户账号 |
| POST /api/admin/users/{id}/enabled | ADMIN：启用/禁用；禁用立即撤销会话 |
| POST /api/agent/tasks | OPERATOR：创建任务；必须带 Idempotency-Key |
| GET /api/agent/tasks/{id} | 发起人或同租户审计/审批/管理员 |
| GET /api/agent/tasks/{id}/report | 同原任务读取权限；返回中文汇报与引用，旧任务只读转换，尚无结果时返回 409 |
| GET /api/agent/tasks | 按身份过滤任务历史，最新 200 条 |
| GET /api/agent/tasks/{id}/events?after=序号 | 已提交事件，单页最多 500 条 |
| GET /api/agent/tasks/{id}/stream | SSE；Last-Event-ID 恢复，连接期间继续校验会话 |
| POST /api/tasks/{id}/approve 或 reject | 非发起人的 APPROVER；必须带 Idempotency-Key |
| GET /api/tools；GET /api/eval/latest；GET /api/eval/real | 工具元数据；历史确定性快照；独立真实模型归档 |
| GET /api/eval/comparison | 原始真实模型基线与当前新版归档；需要认证 |

创建体为 `{"query":"查询安全帽规范"}`。审批体为 `{"reason":"已确认目标及操作","planHash":"从任务读取的摘要"}`。不能通过 Body 中的 `actor` 冒充他人。同一幂等键和同一请求可安全重试，不同内容返回 409。审批人来自认证身份，不来自模型。

## 配置与部署边界

`.env.example` 是中文模板，不会自动加载。敏感配置通过进程环境或受控凭据注入；不要提交 `key.txt`、数据库文件或本地账号文件。

多实例必须共享 PostgreSQL、数据库 schema 和工业接收端；H2 文件模式只用于单应用本机演示。Flyway 负责工作流/认证表迁移。首次使用独立空 schema；既有库必须先备份并审查迁移，不能打开自动 baseline 蒙混通过。首节点完成演示数据初始化后再启动其余节点；实际业务库必须设置 `MINEGUARD_DEMO_DATA_ENABLED=false`。

已实现可运行的安全与恢复核心，不等于通过生产认证。尚未验收 TLS/SSO/MFA、密码轮换、外部网关级限流、审计防篡改、数据保留与备份恢复、数据库/网络分区、设备级 fencing、真实硬件联调。Milvus 需独占预建集合，快照替换不是跨实例原子事务；当前 Embedding 仍是离线哈希向量。

`RECOVERY_REQUIRED` 不开放一键重跑接口：必须核对接收端回执、设备状态与审批有效性后制定处置，避免未知写操作被再次发送。

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [持久化、安全与本轮测试](docs/DURABILITY_SECURITY_ACCEPTANCE.md)
- [DeepSeek 接入](docs/DEEPSEEK_SETUP.md) / [真实评测](docs/DEEPSEEK_ACCEPTANCE.md)
- [规划器 v2 与前端改进验收](docs/PLANNING_IMPROVEMENT.md)
- [自然语言任务汇报与引用溯源](docs/TASK_REPORT.md)
- [工业 API 映射](docs/INDUSTRIAL_API_MAPPING.md)
- [仍需用户提供的资料](docs/COLLABORATION_CHECKLIST.md)
- [历史阶段报告](FINAL_REPORT.md)
