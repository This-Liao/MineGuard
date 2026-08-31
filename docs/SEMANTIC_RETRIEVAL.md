# BGE 语义检索与独立对照评测

当前支持真正的语义 Embedding：Java 调用 OpenAI-compatible `/embeddings`，本地 BGE 服务以 CPU 执行 ONNX 模型。默认离线回归仍使用哈希向量，二者是明确选择的配置，不会在推理失败时静默互相替代。

## 一次性对照结果

30 条新查询与相关文档标注、20 篇知识文档、评分器和模型版本在 `2932cfe` 提交后才执行。两组使用同一语料、分块、内存余弦检索与文档去重规则；没有 Rerank，没有根据结果调参后重跑。

| 文档级指标 | 哈希向量（768 维） | BGE（512 维） |
| --- | ---: | ---: |
| Recall@1 | 51.67% | 65.00% |
| Recall@3 | 83.33% | 96.67% |
| Recall@5 | **86.67%** | **96.67%** |
| MRR@5 | 0.7622 | 0.8778 |
| nDCG@5 | 0.7750 | 0.8975 |
| HitRate@5 | 90.00% | 96.67% |

运行 ID：`2026-08-31T15-58-59.910965400Z-2b771f0f-b96a-45e9-9605-28737196c71e`。语义组共 31 次本地向量请求：1 次文档批处理与 30 次查询；**不是 DeepSeek 请求，也没有模型 API 账单**。指标来自 [完整原始 JSON](eval/retrieval-v1/report.json)。

20 条查询标注一篇相关文档，另 10 条标注两篇。Recall 先逐题计算“找回相关文档数 / 标注相关文档数”，再取宏平均；HitRate 单独衡量是否命中至少一篇。MRR 只看前五篇的首个相关项，nDCG 使用二值相关度并按理想排序归一化。

R06“可燃气体浓度连续偏高……”未找回 `K002-gas-warning`，语义组返回的前五篇及完整标注均保留，没有改标签或删除失败题。BGE 不是所有安全问题都能可靠检索的保证。

这些查询是开发者预先标注的小型、同领域留出集，语料是合成演示文档，不是第三方盲测或实际矿山分布；不能将 96.67% 推广为生产召回率。旧固定用例的哈希 Recall@5=100% 不与本表混用。

## 固定模型与来源

- 基础模型：[BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，中文、512 维、最长 512 tokens。
- 权重：[Xenova 的 ONNX 转换](https://huggingface.co/Xenova/bge-small-zh-v1.5)，revision `75c43b069aac4d136ba6bc1122f995fedcfd2781`，`onnx/model_quantized.onnx`，**INT8**，不是未量化 PyTorch 结果。
- 权重 SHA-256：`15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc`；下载与启动均验证摘要。
- 使用 CLS token + L2 归一化；只有查询加“为这个句子生成表示以用于检索相关文章：”前缀，文档不加。按 BGE 模型卡的检索用法实现。
- 运行库与配置记录在原始报告中；模型权重、虚拟环境和缓存只留本机，不提交 Git。

## 本机启动

需要 Python 3.10–3.12，首次下载需要网络。以下从项目根目录运行，创建隔离虚拟环境，不修改系统 Python：

```powershell
python -m venv data/runtime/semantic-embedding/venv
$embeddingPython = '.\data\runtime\semantic-embedding\venv\Scripts\python.exe'
& $embeddingPython -m pip install -r scripts/embedding/requirements.txt
& $embeddingPython scripts/embedding/server.py --download-only
& $embeddingPython scripts/embedding/server.py
```

服务只监听 `127.0.0.1:18082`。健康检查 `GET /health` 返回模型版本、维度和文件摘要。启动失败、摘要不符或推理失败都会显式报错，不返回哈希假向量。这是本机开发侧车，不是带认证、限流和高可用的公网服务。

在另一个 PowerShell 中设置后端配置，再启动新的工作台或使用已有的保留数据重启脚本：

```powershell
$env:MINEGUARD_EMBEDDING_PROVIDER = 'openai-compatible'
$env:MINEGUARD_EMBEDDING_BASE_URL = 'http://127.0.0.1:18082/v1'
$env:MINEGUARD_EMBEDDING_MODEL = 'BAAI/bge-small-zh-v1.5'
$env:MINEGUARD_EMBEDDING_DIMENSIONS = '512'
$env:MINEGUARD_EMBEDDING_QUERY_PREFIX = '为这个句子生成表示以用于检索相关文章：'
# 新环境：.\scripts\start-local-demo.ps1
# 已有环境：.\scripts\restart-local-demo.ps1 -RunPath '<已有目录>' -UseDeepSeek
```

`.env.example` 只是模板，不自动读取。规划模型与向量模型的 API key 独立；远端 HTTPS Embedding 服务使用 `MINEGUARD_EMBEDDING_API_KEY`，不要复用 DeepSeek key。远端服务需兼容 float 编码、批处理和索引字段；实际返回维度必须与配置一致。

更换模型、维度或 query prefix 后必须重建索引。Milvus 的集合维度也必须一致，使用新集合验收后再切换；不能把 512 维向量写进既有 768 维集合。本次质量对照使用内存向量库，不宣称已完成 BGE + Milvus 联合质量验收。

## 复现与回归

```powershell
# 已启动 18082 服务后；不读取 DeepSeek key，不连接业务数据库
.\scripts\run-retrieval-eval.ps1

# 只测 HTTP 契约，无权重下载、无模型推理
python -m unittest discover -s scripts/embedding -p 'test_*.py' -v
```

检索冻结清单在 `data/eval/retrieval_v1_manifest.json`，含查询、语料和实现的 LF 规范化 SHA-256。运行前检查清单；本工作区 `retrieval-v1-attempt.txt` 防止自动重复覆盖首轮实验。新的仓库副本可以复现，但公开后复测不再算新的留出证据。后续质量优化必须另建版本和对照协议。

Java 契约测试覆盖超时、请求预算、模型/维度/数量/索引错误、非法向量、凭据隔离与禁止降级；Python 契约测试在 CI 中使用明确的测试替身。真实 BGE 推理证据单独记录，不与替身测试混称。
