# 接入增量验收记录（前阶段快照）

本文件保留当时 48+2 项测试及 82.58% 指令覆盖率，不代表加入持久化与认证后的当前覆盖率。后续真实模型与多进程验收见 [当前验收](DURABILITY_SECURITY_ACCEPTANCE.md) 和 [DeepSeek 实测](DEEPSEEK_ACCEPTANCE.md)。下文“未实现/未运行”均描述本快照对应阶段。

验收日期：2026-08-31。本记录与最初的确定性评测快照分开保存；没有修改原来的 Retrieval/Agent/Safety 指标，也没有把本机假模型用量当成真实 DeepSeek 用量。

## 执行与结果

执行入口：`scripts/run-external-it.ps1`，核心命令为 `mvn -q -Pexternal-it clean verify`。另验证了 `run-real-eval.ps1 -MaxCalls 0` 在读取密钥前拒绝执行。此轮未调用真实模型。

| 验证项 | 实际结果 | 原始证据 |
| --- | --- | --- |
| 普通测试 | 48 项，通过 48，失败/异常/跳过均为 0 | `target/surefire-reports/TEST-*.xml` |
| 进程外服务集成测试 | 2 项，通过 2，失败/异常/跳过均为 0 | `target/failsafe-reports/TEST-com.mineguard.integration.ExternalServicesIT.xml` |
| JaCoCo 指令覆盖率 | 8196 / 9925 = **82.58%** | `target/site/jacoco/jacoco.xml` 的顶层 INSTRUCTION counter |
| JaCoCo 行覆盖率 | 1195 / 1427 = 83.74% | 同上，LINE counter |
| JaCoCo 分支覆盖率 | 452 / 758 = 59.63% | 同上，BRANCH counter |

覆盖率是普通测试与外部集成测试在干净构建中的合并结果；分母为当时全部被 JaCoCo 分析的主程序字节码，没有新增排除项。它不是仅单元测试覆盖率，也不是分支覆盖率达到 70%。`verify` 新增指令覆盖率不低于 70% 的构建门槛；纯离线构建的数值应单独以该次报告为准。

若引用简历，可准确表述为“通过 48 项普通测试与 2 项外部数据库集成测试，JaCoCo 指令覆盖率 82.58%”；不能据此声称真实模型效果、设备安全或生产可用性已验证。原有 63.35% 属于此前代码与测试范围，本次不是对旧报告的人工改数。

## 环境与隔离

- Docker Engine：29.6.1，Linux 容器。
- PostgreSQL：16.15，镜像 `postgres:16-alpine`；此次镜像摘要 `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`。
- Milvus：`milvusdb/milvus:v2.5.10`；此次镜像摘要 `sha256:02e1d60d71ab60f435c60076f4fed2abe59602ecd5e18dcfe229c8c558c4379d`。
- 依赖为 `quay.io/coreos/etcd:v3.5.18` 和 `minio/minio:RELEASE.2023-03-20T20-16-18Z`，匹配本机已有 Milvus 演示版本；不是推荐的生产安全版本基线。
- 项目名 `mineguard-integration`；PostgreSQL 使用 `127.0.0.1:15432`，Milvus REST 使用 `127.0.0.1:19540`，健康接口使用 `127.0.0.1:19091`。
- 未启动、重置或删除用户原有的 `milvus-*`、`wavepilot-app` 容器；未使用它们的数据卷。

编排参考 [Milvus 官方 Compose 说明](https://milvus.io/docs/v2.5.x/install_standalone-docker-compose.md)，按本地隔离要求调整端口、容器名称和卷。PostgreSQL 的 `16-alpine` 标签会随补丁版本更新，因此报告同时记录实际版本和摘要，不能仅凭标签声称二次运行完全相同。

## 已验证行为

1. PostgreSQL 真实 JDBC 建表、420 条合成事件初始化、时间/区域/类型筛选及 SQL 聚合；重新创建仓储连接并重复初始化，记录数仍为 420。
2. Milvus 创建随机测试集合，字段包含字符串主键、文档元数据和 768 维向量，建立 COSINE 索引。
3. 实际执行 upsert 与检索；相同 ID 更新后，新客户端可检索到新文本；减少快照时旧块被移除，空快照后检索无结果。
4. 测试仅清理自己创建的 `mineguard_it_<随机ID>` 集合。独立 PostgreSQL 测试库和 Docker 数据卷保留，可继续用于后续验收。
5. 本机假服务验证 DeepSeek 请求字段、Token/缓存统计、修复计数、并发额度拦截、未知用量、截断、429、超时、错误脱敏及独立报告路径。

修复了原 Milvus `replaceAll` 仅 insert、不能更新或移除旧块的问题。现在使用 upsert 后删除旧块，要求目标集合由知识库独占；两个 REST 请求之间没有事务，不具备跨实例原子快照切换能力。

## 未验收与限制

- 未执行真实 DeepSeek 请求，密钥有效性、账号可用模型、网络连通性和真实 Token 数仍待授权后的试跑。
- 数据库验证是直接仓储/适配器集成，未把整个 Web 服务同时切换到 PostgreSQL + Milvus 做全链路验收。
- 尚未验证容器重启、磁盘故障、网络分区、备份恢复、认证/TLS及多实例并发写入；重新创建客户端不等于重启数据库。
- Milvus 正式应用集合仍需预先建立，当前测试建表逻辑不是生产 schema migration；`size()` 为本地元数据缓存大小，不能当作服务端精确行数。
- Agent Task/SSE 持久化、分布式调度、断点恢复、生产认证/RBAC和真实工业设备启停未在本轮实现。

容器当前保留运行。可使用以下命令停止，保留全部验收数据：

```powershell
docker compose -f infra/compose.integration.yml stop
```

这里不提供自动删除数据卷的命令，避免误清理后续验收数据。
