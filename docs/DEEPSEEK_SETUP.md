# DeepSeek 受控评测接入

## 当前状态

客户端与流水线已经通过本机 HTTP 桩测试，并于 2026-08-31 实际完成 DeepSeek 评测。用户已明确本轮不限制调用次数；完整批次 55 次请求、55,397 Token，结果见 [真实验收](DEEPSEEK_ACCEPTANCE.md)。脚本保留每次运行的保护额度，仍不能把模拟用量写为真实指标。

Maven 普通测试固定使用确定性模型、测试凭据、H2 和内存向量库，不继承终端配置的付费模型或外部数据源。需要 HTTP 的客户端测试另启本机假服务。

用户已提供 OpenAI-compatible 基础地址 `https://api.deepseek.com` 和三个模型 ID。脚本默认 `deepseek-v4-flash`，也允许显式选择 `deepseek-v4-pro` 或 `deepseek-v4-flash-vision-exp`。当前用例仅包含文本，不构成视觉模型评测。这里不使用 Anthropic 路径。

接口依据：[DeepSeek Chat Completion 官方文档](https://api-docs.deepseek.com/api/create-chat-completion/)、[思考模式说明](https://api-docs.deepseek.com/guides/thinking_mode/)。脚本使用非流式 JSON 输出、`max_tokens=2048`、`thinking.type=disabled` 和 60 秒请求超时；页面 SSE 是工作流事件流，不是模型 Token 流。

## 密钥与调用授权

- 项目根目录的 `key.txt` 应仅包含密钥一行；`key`、`key.txt` 和 `.env` 已被 Git 忽略。
- Git 忽略不是加密。不要上传整个项目目录、分享该文件或将密钥贴进聊天、README、终端命令参数。
- 脚本通过进程环境传入密钥，结束后恢复原环境，不写入系统永久环境变量。
- `.env.example` 只是模板，Spring Boot 不会自动加载 `.env`。
- 默认 `MINEGUARD_LLM_MAX_CALLS=0`，不允许发送模型请求。重启进程会重置账本，不能用它冒充跨进程预算控制。

首次连通性检查使用以下命令；本轮已实际执行：

```powershell
# 前三条 Agent 用例；每条最多一次计划修复，最多发出六次模型请求。
.\scripts\run-real-eval.ps1 -MaxCalls 6 -AgentCases 3
```

完整固定用例为 30 条 Agent 加 20 条 Safety，参数是 `-MaxCalls 100 -AgentCases 30 -SafetyCases 20`。本轮已授权并运行。次数包括发送失败、超时、限流以及计划修复；不自动退还或重试。其他未获费用授权的环境中，API key 配置成功不等于任意费用授权。

只给货币预算而没有调用次数时，先根据当时模型价格、输入/输出上限及服务商侧额度控制确认方案。当前没有实现硬性货币预算，调用次数乘输出上限也不是总费用上限。

## 环境隔离与报告

`RealModelEvalApplication` 固定使用独立 H2 内存库、合成知识、内存向量库和 `MockIndustrialGateway`，并检查网关类型。测试框架可能批准高风险用例，但只作用于模拟设备，不会调用 PDF 中的真实启停接口。不接受覆盖这组配置的命令行参数。

每次运行新建 `data/runtime/real-model-eval/<时间-UUID>/`，包含 `report.json`、中文 `REPORT.md` 和 Trace。不会覆盖 `docs/eval/latest.json`、原始确定性指标或简历指标。

状态含义：

- `COMPLETED`：所选用例执行结束，不保证每条成功；查看 `agent.cases` 和 `safety.cases`。
- `INCOMPLETE_BUDGET`：执行期间出现本地额度拒绝，不可当作完整评测结论。
- `ABORTED`：流程异常中止，已取得的用量仍尽量保留；如果进程被强制终止或机器断电，内存账本尚不能保证落盘。

小样本按原始文件顺序选取，不是随机抽样；不用于显著性判断。关键词静态基线不参与真实模型端到端对比。

## Token 指标口径

| 字段 | 含义 |
| --- | --- |
| `attempts` / `maxCalls` | 已占用的请求次数 / 本进程授权上限 |
| `requestsWithUnknownUsage` | 未拿到完整输入、输出、总 Token 回执的请求数，包含未完成请求 |
| `recordedPromptTokens` / `recordedCompletionTokens` / `recordedTotalTokens` | 已有供应商回执之和；部分回执之和不是整轮总消耗 |
| `recordedPromptCacheHitTokens` / `recordedPromptCacheMissTokens` | DeepSeek 返回的缓存命中 / 未命中输入 Token |
| `recordedReasoningTokens` | 服务端返回的推理 Token 数量，不含思维链文本 |
| `usageComplete` | 所有已尝试请求是否都有完整的核心 Token 回执 |
| `calls` | 每次请求的序号、时间、耗时、HTTP 状态、结果类别和数值用量 |

`null` 表示缺失，不是零。回复被截断但返回了 usage 时仍计入消耗；超时、429 或无 usage 时不推算免费。输出 Token 已含服务商计入的推理 Token，不能再次相加。账单仍以服务商为准。
