# 持续集成

## 每次推送与 Pull Request

[CI 工作流](../.github/workflows/ci.yml) 在 GitHub 托管的 Ubuntu 24.04 runner 上运行：

- Java 21：`mvn verify`，包含离线测试与 JaCoCo 指令覆盖率 ≥ 70% 门禁。
- Python：向量侧车的 4 项 HTTP 契约测试，使用明确的测试替身，不下载权重、不发起推理请求。
- Node.js 22：`npm ci` → `npm test` → `npm run build`，包含交互测试、类型检查和生产构建。
- 后端和前端为两个独立作业；任意一个失败，不能将整次工作流报告为通过。
- 测试报告和覆盖率报告保存 14 天；失败时也上传已生成的报告。

使用最小 `contents: read` 权限，不使用 `pull_request_target`，不注入 DeepSeek 或生产数据源密钥。Actions 依赖锁定到完整提交 SHA，checkout 不持久化写仓库凭据。普通测试固定离线模型与内存向量库，不需要外部模型或数据库。

## 外部服务验收

[External Integration](../.github/workflows/external-integration.yml) 支持手动运行，每日 UTC 18:17（北京时间次日 02:17）也会触发。GitHub 的定时任务可能排队延迟，不作为精确计时器。

在每次新建的临时 runner 内启动仓库专用 Compose 环境，等待 PostgreSQL 和 Milvus 健康，再执行 `mvn -Pexternal-it verify`。`MINEGUARD_RUN_EXTERNAL_IT=true` 启用三个外部用例，覆盖实际 SQL、Milvus 读写替换与独立 JVM 故障接管/SSE 重放。

结束时仅清理该临时 runner 的 `mineguard-integration` Compose 项目和卷；不操作开发机上已运行的数据库。报告不包含本地账号文件、密钥文件或完整运行目录。

外部工作流额外归档测试节点日志与分布式结果摘要，便于诊断跨平台启动和故障注入问题。独立 JVM 根据操作系统选择当前 JDK 的 `java` / `java.exe`，显式使用离线哈希检索，不继承开发机的向量服务设置。

## 徽章与失败处理

README 的 CI 徽章链接到真实 Actions 状态，不代表本地验收历史。推送完成但 GitHub 尚未执行时，不把它写成绿色通过。

失败先查看具体 job 和归档报告；禁止通过跳过失败测试、去掉覆盖率门禁或设置 `continue-on-error` 来变绿。首次贡献者的 PR 可能需要维护者在 GitHub 界面批准运行，这是平台保护，不应绕过。

参考：[GitHub Maven CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)、[Node.js CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs)。
