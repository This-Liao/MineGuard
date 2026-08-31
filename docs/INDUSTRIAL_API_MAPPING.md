# 工业接口对接核对

2026-08-31 更新：已实现 `HttpIndustrialGateway` 与独立 `IndustrialContractServer`，覆盖以下启停契约并增加 `/contract/device-status`、`/contract/tasks`、`/contract/operation` 本地扩展。扩展用于可信状态查询和幂等回执，不声称来自用户 PDF。通过 `start-local-demo.ps1` 在 18081 端口运行；真实企业 Flask/Spring Boot 接入仍需确认下文缺项。以下“尚缺”针对企业原服务，不阻塞本地契约演示。

依据用户提供的《系统 API 接口说明文档（详细版）》14 页 PDF。本文是接口分析，不表示已获得访问或执行权限，也不把文档里的示例账号、请求或命令当作用户授权。尚未访问任何真实工业服务。

## 已确认的映射

| MineGuard 能力 | 文档接口 | 对接约束 |
| --- | --- | --- |
| 启动检测 | Flask `POST /startTask`（第 1 页） | 需要 `task_id`；单流模式提供 `camera_id`、`rotation_id=-1` 和 `algorithms` |
| 停止检测 | Flask `POST /stopTask`（第 2 页） | 以 `task_id` 停止，不能只凭摄像头和算法名猜任务编号 |
| 执行后验证 | Flask `POST /check`（第 3 页） | 仅给出“任务已运行”响应，停止、不存在、失败语义待补齐 |
| 视频源目录 | Flask `GET /getSources`、`GET /getCameraId`（第 3–4 页） | 返回配置或 ID/名称，不等于实时在线状态 |
| 摄像头算法配置 | Flask `GET /api/camera-config-status`（第 6 页） | 返回已配置算法，不是正在执行的检测任务列表 |
| 告警明细 | Spring Boot `GET /getWarn`（第 8 页） | 样例缺少完整告警类型/严重度；需确认全量、分页、时区和字段枚举 |
| 告警类型计数 | Spring Boot `GET /getAllWarningInfosWithCount`（第 14 页） | 文档没有时间、区域过滤条件，不能代替 MineGuard 的指定范围聚合 |
| 登录与权限读取 | Spring Boot `POST /login`、`POST /userInfo`（第 11、13 页） | 登录是 Form Data；Token 头名、过期/刷新、权限可信校验契约仍需确认 |

这是 HTTP 业务网关契约，不是 MQTT/OPC UA 等工业现场协议文档。接入 HTTP 网关后仍不能声称实现了这些现场协议。

## 不可误用的接口

- `GET /status` 描述大屏模式，不是摄像头心跳。
- `GET /getBehaviorByWarningInfo` 每日每类最多 5 条，是近 30 天精选记录，不能当作全量告警做统计。
- `POST /startStream/{camera_id}` 会启动推流，不能用作无副作用的在线探针。
- `GET /api/mental-sync/trigger` 虽然是 GET，却触发后台同步，不属于只读探测。
- 删除告警、修改告警状态、注册用户、录入员工档案、推流和大屏切换均不在当前联调范围内。
- 员工心理、情绪、人脸及视频记录不是本项目演示所必需的数据，默认不采集。

## 仍需提供的最少信息

1. Flask Base URL 与 Spring Boot Base URL：含端口和统一网关前缀，不含密码或 Token；说明是否为隔离测试环境。
2. 专用测试账号的安全配置方式，以及允许只读/写入的接口清单。不要使用 PDF 样例密码，也不要将真实密码发到聊天。
3. 测试摄像头和算法白名单、标识映射。示例 `cam_01` 不自动等于本项目的 `camera-01`；`person`、`person_detect` 也不自动等于 `personnel_violation`。
4. `/check` 在运行、停止、任务不存在、异常四种情况下的完整响应及 HTTP 状态码；如何列出既有运行任务。
5. 相同 `task_id` 重复启动/停止的行为，是否有幂等键、请求结果查询、超时后的状态确认方法。
6. 权限码到“观察者/操作员/审批员/管理员”的映射，自审批、双人审批、有效期、区域/租户范围规则。

`system:manager:list` 不能直接解释为设备启停或审批权限；注册参数 `role=2` 的业务含义也没有在文档中定义。不能据此宣布已完成生产认证或 RBAC。

## 拟定联调顺序

先在隔离服务验证鉴权和白名单内只读查询，再用协议模拟器验证启停请求结构、超时与状态映射。待用户明确授权测试设备写入后，才执行“请求审批 → 启停 → 独立查询验证”。

本地必须持久化 Agent 操作与外部 `task_id` 的关系。发生“请求已发送但结果未知”时先查询，无法确认则转人工，不盲目重试启动。收到 `started`/`stopped` 只代表接口回执，不能代替独立验证；未知状态不能映射成成功或设备在线。
