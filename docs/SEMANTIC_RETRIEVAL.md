# BGE、Milvus、BM25 与 RRF 混合检索

当前支持真正的语义 Embedding：Java 调用 OpenAI-compatible `/embeddings`，本地 BGE 服务以 CPU 执行 ONNX 模型。应用默认并行执行已配置 `VectorStore` 的向量检索与 Lucene BM25，再用 RRF 统一排序；选择 Milvus 时即为 Milvus + BM25。普通离线测试显式固定为哈希向量单路。任一真实通道失败都会显式报错，不静默换成另一套实现。

## v3 扩展混合检索评测

2026-09-13 将主对照从 20 篇文档、30 条查询扩大到 **40 篇文档、80 条查询**。新增的 20 篇相似文档仅位于评测目录，不改变工作台现有知识库；查询按语义改写 20 条、精确标识 30 条、标识与自然语言混合 30 条分层。全部输入、实现、BGE revision、`candidateK=40` 与 `rrfK=60` 在运行前写入 [冻结清单](../data/eval/hybrid_retrieval_v3_manifest.json)。

| 文档级指标 | BGE + Milvus 向量 | Lucene BM25 | 等权 RRF |
| --- | ---: | ---: | ---: |
| Recall@1 | 58.75% | 66.88% | **68.13%** |
| Recall@3 | 82.08% | **92.92%** | 91.25% |
| Recall@5 | 91.25% | **97.08%** | 96.25% |
| MRR@5 | 0.8333 | **0.9240** | 0.9140 |
| nDCG@5 | 0.8385 | **0.9160** | 0.9145 |
| HitRate@5 | 92.50% | **100.00%** | 97.50% |

RRF 相比向量单路的 Recall@5 提高 **5.00 个百分点**，MRR@5 提高 0.0806，nDCG@5 提高 0.0760；80 条中有 4 条 Recall@5 改善、0 条退化，4 条向量完全漏检的查询被融合结果找回。RRF 没有超过这批数据上的 BM25 总体 Recall@5，因此当前结论是“融合显著补偿向量漏检”，不是“RRF 在所有数据上优于任意单路”。

| 查询类别 | 向量 Recall@5 | BM25 Recall@5 | RRF Recall@5 |
| --- | ---: | ---: | ---: |
| 语义改写 | 95.00% | 100.00% | **100.00%** |
| 精确标识 | 83.33% | **100.00%** | 93.33% |
| 混合表达 | 96.67% | 92.22% | **96.67%** |

这组分层结果解释了总体数字：BM25 在精确编号上最强，BGE 在混合自然语言上更稳，RRF 在不读取两路原始分数的情况下兼顾两者，但等权融合会在纯精确标识切片上被较弱的向量排名拖低。

## 独立扰动鲁棒性评测

鲁棒性套件与 80 条主对照完全分离，包含 **16 个检索意图、每组 4 种表达，共 64 条查询**。其中 8 组是设备/算法标识与处置意图混合查询，8 组是纯语义查询；每组固定相同相关文档，并分别使用基础表达、语义改写、术语或格式变化、无关上下文噪声。评测器同时计算逐题指标、分切片指标、RRF 挽救/损伤、整组稳定命中、组内最差召回与首个相关文档排名波动。

| 鲁棒性指标 | BGE + Milvus 向量 | Lucene BM25 | 等权 RRF |
| --- | ---: | ---: | ---: |
| Recall@1 | 46.09% | 47.40% | **48.96%** |
| Recall@3 | 70.31% | 76.56% | **82.55%** |
| Recall@5 | 81.51% | 88.02% | **91.67%** |
| MRR@5 | 0.8227 | 0.8750 | **0.8867** |
| nDCG@5 | 0.7503 | 0.8072 | **0.8425** |
| HitRate@5 | 98.44% | 98.44% | **100.00%** |
| 16 组全部变体均命中 | 93.75% | 93.75% | **100.00%** |
| 平均组内最差 Recall@5 | 62.50% | 70.83% | **79.17%** |
| 平均首个相关文档排名跨度 | 1.625 | 1.188 | **0.875** |
| 扰动相对基础表达的平均 Recall@5 变化 | -12.15 个百分点 | -4.86 个百分点 | **-2.78 个百分点** |

RRF 相比向量单路在 64 条中有 **15 条改善、1 条退化、48 条持平**；将 1 条向量完全漏检变为命中，没有把任何向量已命中的查询变成完全漏检。编号混合切片的 Recall@5 为 75.52% / 82.29% / **88.02%**，纯语义切片为 87.50% / 93.75% / **95.31%**，因此提升并非只来自堆叠设备编号样本。

按扰动类型观察，RRF 在基础表达、语义改写、术语变化和噪声上下文上的 Recall@5 分别为 **93.75% / 92.71% / 85.42% / 94.79%**，均高于两条单路。术语与格式变化仍是最难切片，说明融合降低了波动，但没有消除跨语言缩写、空格和标识格式变化造成的召回损失。

本次实际产生 146 次本地 BGE HTTP 请求：40 个文档分成 2 个批次，另有 144 条查询请求。向量写入 Milvus 2.5 随机隔离集合，成功结束后已删除。首次启动容器时在任何查询执行前因 Milvus 元数据尚未就绪而中止；待服务健康后使用未改动的冻结清单完成本次运行。完整逐题三路排名、分层结果和成组鲁棒性明细见 [v3 原始 JSON](eval/hybrid-retrieval-v3/report.json)。

## Milvus + BM25 + RRF 三路回归

2026-09-12 使用同一批 30 条固定查询、20 篇知识文档和相同文档级评分器，完成了真实 BGE + Milvus、Lucene BM25 和等权 RRF 三路对照。查询、语料、实现、模型版本与 `candidateK=20`、`rrfK=60` 在运行前写入冻结清单。

| 文档级指标 | BGE + Milvus 向量 | Lucene BM25 | RRF 融合 |
| --- | ---: | ---: | ---: |
| Recall@1 | 65.00% | 56.67% | 56.67% |
| Recall@3 | 96.67% | 86.67% | 90.00% |
| Recall@5 | **96.67%** | **90.00%** | **93.33%** |
| MRR@5 | 0.8778 | 0.8111 | 0.8194 |
| nDCG@5 | 0.8975 | 0.8224 | 0.8431 |
| HitRate@5 | 96.67% | 90.00% | 93.33% |

本轮共发起 31 次本地 BGE 请求（1 次文档批处理、30 次查询），向量实际写入 Milvus 2.5 的随机隔离集合，完成后集合已删除。原始报告保存每题的向量、BM25 与融合排名：[完整 JSON](eval/hybrid-retrieval-v2/report.json)。

等权 RRF 在这批以自然语言改写为主的查询上没有超过语义向量单路，因此不能将 93.33% 描述为检索质量提升。它补齐了关键词通道和可审计分路排名；`安全帽`、`CAMERA-19`、`intrusion_detection` 的精确词能力由 Lucene 契约测试单独覆盖。后续若调整通道权重或增加 Rerank，应另建数据集，避免拿本轮结果反向调参后仍称留出评测。

## 历史语义向量对照

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
$env:MINEGUARD_VECTOR_STORE = 'milvus'
$env:MILVUS_URI = 'http://127.0.0.1:19540'
$env:MINEGUARD_RETRIEVAL_MODE = 'hybrid'
$env:MINEGUARD_RETRIEVAL_CANDIDATE_K = '20'
$env:MINEGUARD_RETRIEVAL_RRF_K = '60'
# 新环境：.\scripts\start-local-demo.ps1
# 已有环境：.\scripts\restart-local-demo.ps1 -RunPath '<已有目录>' -UseDeepSeek
```

`.env.example` 只是模板，不自动读取。规划模型与向量模型的 API key 独立；远端 HTTPS Embedding 服务使用 `MINEGUARD_EMBEDDING_API_KEY`，不要复用 DeepSeek key。远端服务需兼容 float 编码、批处理和索引字段；实际返回维度必须与配置一致。

更换模型、维度或 query prefix 后必须重建索引。Milvus 的集合维度也必须一致，使用新集合验收后再切换；不能把 512 维向量写进既有 768 维集合。历史 v1 质量对照使用内存向量库；上面的 v2 三路回归已经完成 BGE + Milvus + Lucene 联合验收。

## 复现与回归

```powershell
# 已启动 18082 服务后；不读取 DeepSeek key，不连接业务数据库
.\scripts\run-retrieval-eval.ps1

# 自动启动隔离 Milvus；需要本机已准备 BGE 模型与虚拟环境
.\scripts\run-hybrid-retrieval-eval.ps1

# 只测 HTTP 契约，无权重下载、无模型推理
python -m unittest discover -s scripts/embedding -p 'test_*.py' -v
```

历史语义对照冻结清单在 `data/eval/retrieval_v1_manifest.json`；三路回归清单在 `data/eval/hybrid_retrieval_v2_manifest.json`；扩展与鲁棒性清单在 `data/eval/hybrid_retrieval_v3_manifest.json`。它们均记录查询、语料、实现和模型配置摘要。v2 复用已经公开的 v1 查询，只用于同数据版本比较；v3 的 64 条扰动查询与 80 条主对照分开计分，不将同一意图的四种表达冒充 64 个独立意图。

Java 契约测试覆盖超时、请求预算、模型/维度/数量/索引错误、非法向量、凭据隔离与禁止降级；Python 契约测试在 CI 中使用明确的测试替身。真实 BGE 推理证据单独记录，不与替身测试混称。
