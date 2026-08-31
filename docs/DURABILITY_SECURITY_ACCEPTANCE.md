# 任务持久化、分布式恢复与安全验收

验收日期：2026-08-31。本报告对应加入持久化、认证/RBAC和工业 HTTP 契约后的代码；不覆盖最初的确定性评测快照，也不把本地工业替身称为真实设备。

## 最终构建结果

执行 `scripts/run-external-it.ps1`，内部为 `mvn -q -Pexternal-it clean verify`；另执行前端 `npm run build`。最终全部通过。

| 项目 | 实测结果 | 原始证据 |
| --- | --- | --- |
| 普通测试 | 82 项，失败/异常/跳过均为 0 | target/surefire-reports/TEST-*.xml |
| 外部集成测试 | 3 项，失败/异常/跳过均为 0 | target/failsafe-reports/TEST-*.xml |
| JaCoCo 指令覆盖率 | 12,279 / 14,572 = **84.26%** | target/site/jacoco/jacoco.xml |
| JaCoCo 行覆盖率 | 1602 / 1869 = 85.71% | 同上，LINE |
| JaCoCo 分支覆盖率 | 729 / 1144 = 63.72% | 同上，BRANCH |
| 前端 | TypeScript 检查与 Vite 生产构建通过 | npm run build |

[机器可读汇总](eval/engineering-2026-08-31.json) 保存计数、覆盖率、分布式报告及关键原始文件 SHA-256。

指令覆盖率超过 70% 构建门槛，没有新增排除规则或减少业务字节码分母。覆盖率合并 Surefire/Failsafe 中受 JaCoCo 插桩的测试进程；故障测试启动并强杀的应用子 JVM 未插桩，其执行不计入该覆盖率。真实 DeepSeek 评测也不拿来充测试覆盖率。分支覆盖率仍未达到 70%，不能混淆指标。

此前 63.35%、前阶段 82.58% 对应不同代码和测试范围，均不是当前数值。

## 已完成的恢复与幂等验证

`ExternalWorkflowIT` 实际使用外部 PostgreSQL 16.15，启动五个独立 Spring Boot JVM（最多同时两个），模型为本地 HTTP 桩，工业侧为独立 HTTP 契约服务：

1. 节点 A 在 PLANNING 等待模型时被强制终止，节点 B 在数据库租约到期后完成接管，fence 至少递增一次。最终一轮测得从杀进程到完成为 **6366 ms**，不是生产恢复 SLA。
2. 高风险任务到达 WAITING_APPROVAL 后，所有应用进程退出；新节点重新读取同一任务、计划摘要和待审批状态，没有提前写入工业目标。
3. PostgreSQL 中的登录会话跨节点有效。两个节点并发提交同一审批幂等键，仅一条审批记录、一次工业命令；之后独立查询验证成功。
4. 已完成任务有 21 条连续事件；另一节点携带 Last-Event-ID=2，完整补发后续 19 条，无缺口和重复。
5. 精确注入“接收端已提交、应用检查点未完成”的窗口：有不可变回执的任务补记并验证，没有回执的任务进入 RECOVERY_REQUIRED。恢复期间新增重复工业命令为 0。

原始目录：`data/runtime/distributed-it/workflow_it_21a7c572b7654b55897f25b9f0d64066/`，包含 report.json、每个节点日志与 Trace。该测试对关键失败窗口直接构造数据库检查点，是明确的故障注入；不是在真实设备上试验断电。

普通测试额外覆盖并发创建幂等、旧租约不能更新/续约/释放新节点任务、审批过期、计划篡改、禁用审批人、错误回执、回执查询不可用、已记录失败不得在恢复时被跳过。

## 认证和权限验证

- 匿名和 URL 中 Token 被拒绝；密码保存 BCrypt 摘要，Bearer 随机值仅保存 SHA-256 摘要。
- 登录/注销/到期/禁用后失效；五次错误密码导致数据库持久化锁定。
- OBSERVER 不能发起；OPERATOR 不能管理用户；ADMIN 没有隐式审批权。
- 任务发起人即使也有 APPROVER 仍不能自批，伪造 Body.actor 不改变认证身份。
- 跨租户读取任务、事件与 SSE 被拒绝；同租户普通操作员也不能查看他人任务。
- 审批与执行之间禁用审批人，会阻止尚未发出的工业写请求。
- 参数校验错误不回显密码。DeepSeek 密钥文件被 Git 忽略，按实际密钥进行的项目内容检索未发现匹配。
- 就绪探针覆盖 ApplicationRunner 初始化窗口，避免端口已监听但管理员尚未创建时误报就绪；复测通过。

这是已实现并测试的安全核心，不是完成生产安全认证。公网部署仍需 TLS、身份治理、密码轮换、网关限流、备份与防篡改审计等。

## PostgreSQL、Milvus 与工业范围

沿用独立 `mineguard-integration` Docker 项目：PostgreSQL 127.0.0.1:15432、Milvus REST 127.0.0.1:19540；没有修改用户原有容器或数据卷。Milvus 实测集合创建、索引、upsert、替换、查询与新客户端读取；PostgreSQL 实测事件查询、聚合、Flyway、任务/SSE、认证会话、审批和多进程租约。

测试结束仅删除自己创建的随机 PostgreSQL schema 和 Milvus collection；这些临时测试数据可重建，用户数据库和 Docker 数据卷均保留。数据库容器仍在运行。

目前没有把整个应用在 PostgreSQL + Milvus + DeepSeek + 企业工业网关组合上做生产全链路验收。多进程测试用内存向量库，Milvus 适配单独测试；真实模型用独立 H2/Mock 工具，以免自动审批触及实际工业目标。

工业 HTTP 服务由 Java 提供，兼容 PDF 已明确的启停请求字段；/contract/*、停止/不存在状态及幂等回执均为本地明确扩展，不冒充 PDF 原服务已有能力。不声称已经接入 Flask、MQTT、OPC UA、摄像头或矿山现场。

## 本机演示状态

已实际执行 `scripts/start-local-demo.ps1 -UseDeepSeek -SkipBuild`，Spring Boot 与工业服务健康检查 UP；浏览器确认登录页正常显示，前端构建通过。

- 前端：http://127.0.0.1:5173
- Spring Boot：http://127.0.0.1:8080
- 本地工业契约：http://127.0.0.1:18081
- 本次文件数据库、日志、受限账号文件：`data/runtime/local-demo/20260831-145613/`

浏览器技能的凭据安全规则阻止自动代填账号密码，因此**登录后的浏览器交互验收留给用户手动完成**；不把后端自动化测试冒充浏览器端到端测试。用两个页面分别登录 demo-operator / demo-approver，提交任务、展开完整计划并由另一账号审批。

停止本次服务：`scripts/stop-local-demo.ps1 -RunPath 'D:\LLM\MineGuard\data\runtime\local-demo\20260831-145613'`。只结束本次记录且启动时间一致的进程，不删数据库和日志。

## 可用于简历的准确表述

“基于 Spring Boot、PostgreSQL 实现 Agent 任务/事件持久化、租约与 fencing 恢复、审批参数绑定和 RBAC；通过 82 项普通测试与 3 项外部集成测试，JaCoCo 指令覆盖率 84.26%；独立 JVM 故障注入验证跨节点接管与 SSE 重放。”

可另说明实际完成 DeepSeek 30 条 Agent + 20 条安全用例评测、55 次请求及 55,397 Token；严格任务成功率 30%，不能用旧确定性 100% 替代。细节见 [真实模型验收](DEEPSEEK_ACCEPTANCE.md)。
