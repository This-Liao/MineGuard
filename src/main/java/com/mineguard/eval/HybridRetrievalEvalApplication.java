package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.EmbeddingProperties;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.config.RetrievalProperties;
import com.mineguard.rag.Evidence;
import com.mineguard.rag.KnowledgeLoader;
import com.mineguard.rag.KnowledgeRetriever;
import com.mineguard.rag.LuceneBm25Index;
import com.mineguard.rag.MilvusVectorStore;
import com.mineguard.rag.OpenAiCompatibleEmbeddingClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;

/** 真实 BGE + Milvus 向量通道与 Lucene BM25、RRF 的三路对照评测。 */
public final class HybridRetrievalEvalApplication {
    private static final Path CASES = Path.of("data/eval/retrieval_holdout_v1.json");
    private static final Path MANIFEST = Path.of("data/eval/hybrid_retrieval_v2_manifest.json");
    private static final String MODEL = "BAAI/bge-small-zh-v1.5";
    private static final String REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781";
    private static final String PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final String MILVUS = "http://127.0.0.1:19540";
    private static final int DIMENSIONS = 512;
    private static final int CANDIDATE_K = 20;
    private static final int RRF_K = 60;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HybridRetrievalEvalApplication() {}

    public static void main(String[] args) throws Exception {
        KnowledgeLoader loader = new KnowledgeLoader(new MineGuardProperties(null, null, "data/knowledge", "", 1));
        SemanticRetrievalEvalApplication.validateCases(MAPPER.readTree(CASES.toFile()), loader.load());
        Map<String, Object> inputs = snapshot();
        if (args.length == 1 && "--freeze".equals(args[0])) {
            Files.writeString(MANIFEST, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("createdAt", Instant.now().toString(), "inputs", inputs)), StandardOpenOption.CREATE_NEW);
            System.out.println("混合检索实现、评测集、语料和参数已冻结；尚未执行评测。");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("只支持 --freeze 或无参数评测");
        JsonNode manifest = MAPPER.readTree(MANIFEST.toFile());
        if (!manifest.path("inputs").equals(MAPPER.valueToTree(inputs))) throw new IllegalStateException("混合检索冻结输入已变化");
        JsonNode modelMetadata = requireEmbeddingService();

        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        String collection = "mineguard_hybrid_eval_" + UUID.randomUUID().toString().replace("-", "");
        Path directory = Path.of("data/runtime/hybrid-retrieval-eval", runId);
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("startedAt", Instant.now().toString());
        report.put("manifest", manifest);
        report.put("modelProvenance", modelMetadata);
        report.put("protocol", "复用 retrieval-holdout-v1 的 30 条固定查询，比较同一语料上的向量、BM25 与 RRF；属于版本回归，不是新增盲测");
        report.put("environment", "本地 CPU BGE INT8 实际推理；Milvus 2.5 随机隔离集合；Lucene 10.3.1 BM25；RRF(k=60)");
        boolean created = false;
        try {
            createCollection(collection);
            created = true;
            var embedding = new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties("openai-compatible",
                    "http://127.0.0.1:18082/v1", "", MODEL, DIMENSIONS, 60, 100, PREFIX), MAPPER);
            var store = new MilvusVectorStore(MILVUS, MAPPER, collection);
            try (var bm25 = new LuceneBm25Index(); var executor = Executors.newFixedThreadPool(2)) {
                var retriever = new KnowledgeRetriever(loader, embedding, store, bm25, executor,
                        new RetrievalProperties("hybrid", CANDIDATE_K, RRF_K));
                retriever.index();
                Evaluation evaluation = evaluate(retriever);
                report.put("indexedChunks", retriever.indexedChunkCount());
                report.put("bm25IndexedChunks", retriever.bm25IndexedChunkCount());
                report.put("embeddingRequests", embedding.requestCount());
                report.put("vector", RetrievalBenchmark.summarize(evaluation.vector()));
                report.put("bm25", RetrievalBenchmark.summarize(evaluation.bm25()));
                report.put("rrf", RetrievalBenchmark.summarize(evaluation.rrf()));
                report.put("rankings", evaluation.rankings());
            }
            report.put("status", "COMPLETED");
        } catch (Exception ex) {
            report.put("status", "ABORTED");
            report.put("error", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            if (created) {
                try {
                    post("collections/drop", Map.of("collectionName", collection));
                    report.put("isolatedCollectionDropped", true);
                } catch (Exception ex) {
                    report.put("isolatedCollectionDropped", false);
                }
            }
            report.put("finishedAt", Instant.now().toString());
            var writer = MAPPER.writerWithDefaultPrettyPrinter();
            writer.writeValue(directory.resolve("report.json").toFile(), report);
            if ("COMPLETED".equals(report.get("status"))) {
                Path archive = Path.of("docs/eval/hybrid-retrieval-v2/report.json");
                Files.createDirectories(archive.getParent());
                writer.writeValue(archive.toFile(), report);
            }
            System.out.println("混合检索评测报告：" + directory.toAbsolutePath());
        }
    }

    private static Evaluation evaluate(KnowledgeRetriever retriever) throws Exception {
        List<RetrievalEvaluator.Case> cases = MAPPER.readValue(CASES.toFile(), new TypeReference<>() {});
        List<RetrievalBenchmark.CaseResult> vector = new ArrayList<>();
        List<RetrievalBenchmark.CaseResult> bm25 = new ArrayList<>();
        List<RetrievalBenchmark.CaseResult> rrf = new ArrayList<>();
        List<Map<String, Object>> rankings = new ArrayList<>();
        for (RetrievalEvaluator.Case item : cases) {
            var result = retriever.retrieveDetailed(item.query(), 5);
            List<String> vectorIds = documentIds(result.vector());
            List<String> bm25Ids = documentIds(result.bm25());
            List<String> rrfIds = documentIds(result.fused());
            vector.add(RetrievalBenchmark.score(item.id(), item.query(), item.expectedDocumentIds(), vectorIds));
            bm25.add(RetrievalBenchmark.score(item.id(), item.query(), item.expectedDocumentIds(), bm25Ids));
            rrf.add(RetrievalBenchmark.score(item.id(), item.query(), item.expectedDocumentIds(), rrfIds));
            rankings.add(Map.of("id", item.id(), "vector", vectorIds, "bm25", bm25Ids, "rrf", rrfIds));
        }
        return new Evaluation(vector, bm25, rrf, rankings);
    }

    private static List<String> documentIds(List<KnowledgeRetriever.RankedEvidence> ranking) {
        return ranking.stream().map(KnowledgeRetriever.RankedEvidence::evidence)
                .map(Evidence::documentId).distinct().limit(5).toList();
    }

    private static JsonNode requireEmbeddingService() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/health"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode metadata = MAPPER.readTree(response.body());
        if (response.statusCode() != 200 || !"UP".equals(metadata.path("status").asText())
                || !MODEL.equals(metadata.path("model").asText()) || !REVISION.equals(metadata.path("revision").asText())
                || metadata.path("dimensions").asInt() != DIMENSIONS || !"INT8".equals(metadata.path("quantization").asText())) {
            throw new IllegalStateException("本地 BGE 服务与冻结配置不一致");
        }
        return metadata;
    }

    private static void createCollection(String collection) throws Exception {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("fieldName", "id", "dataType", "VarChar", "isPrimary", true,
                "elementTypeParams", Map.of("max_length", "512")));
        for (String field : List.of("documentId", "title", "chunkId", "content")) {
            fields.add(Map.of("fieldName", field, "dataType", "VarChar",
                    "elementTypeParams", Map.of("max_length", "65535")));
        }
        fields.add(Map.of("fieldName", "vector", "dataType", "FloatVector",
                "elementTypeParams", Map.of("dim", String.valueOf(DIMENSIONS))));
        post("collections/create", Map.of("collectionName", collection,
                "schema", Map.of("autoId", false, "enabledDynamicField", false, "fields", fields),
                "indexParams", List.of(Map.of("fieldName", "vector", "indexName", "vector_idx",
                        "indexType", "AUTOINDEX", "metricType", "COSINE")),
                "params", Map.of("consistencyLevel", "Strong")));
    }

    private static JsonNode post(String endpoint, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MILVUS + "/v2/vectordb/" + endpoint))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Milvus HTTP " + response.statusCode());
        JsonNode result = MAPPER.readTree(response.body());
        if (result == null || result.path("code").asInt(-1) != 0) {
            String message = result == null ? "空响应" : result.path("message").asText("未提供原因")
                    .replaceAll("[\\r\\n]", " ");
            if (message.length() > 240) message = message.substring(0, 240);
            throw new IllegalStateException("Milvus " + endpoint + " 未成功，code="
                    + (result == null ? -1 : result.path("code").asInt(-1)) + "，原因=" + message);
        }
        return result;
    }

    private static Map<String, Object> snapshot() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        List<Path> paths = new ArrayList<>(List.of(CASES,
                Path.of("pom.xml"),
                Path.of("src/main/java/com/mineguard/eval/HybridRetrievalEvalApplication.java"),
                Path.of("src/main/java/com/mineguard/eval/RetrievalBenchmark.java"),
                Path.of("src/main/java/com/mineguard/rag/KnowledgeRetriever.java"),
                Path.of("src/main/java/com/mineguard/rag/LuceneBm25Index.java"),
                Path.of("src/main/java/com/mineguard/rag/MilvusVectorStore.java"),
                Path.of("src/main/java/com/mineguard/rag/OpenAiCompatibleEmbeddingClient.java"),
                Path.of("scripts/embedding/server.py"), Path.of("scripts/embedding/requirements.txt")));
        try (var files = Files.list(Path.of("data/knowledge"))) {
            paths.addAll(files.filter(path -> path.toString().endsWith(".md")).sorted().toList());
        }
        for (Path path : paths) hashes.put(path.toString().replace('\\', '/'), HoldoutGuard.textHash(path));
        return Map.ofEntries(
                Map.entry("suite", "hybrid-retrieval-regression-v2"), Map.entry("caseCount", 30),
                Map.entry("model", MODEL), Map.entry("revision", REVISION), Map.entry("dimensions", DIMENSIONS),
                Map.entry("queryPrefix", PREFIX), Map.entry("quantization", "INT8"),
                Map.entry("vectorStore", "Milvus 2.5/COSINE/AUTOINDEX/Strong"),
                Map.entry("bm25", "Lucene 10.3.1/CJKAnalyzer/BM25Similarity"),
                Map.entry("candidateK", CANDIDATE_K), Map.entry("rrfK", RRF_K), Map.entry("hashes", hashes));
    }

    private record Evaluation(List<RetrievalBenchmark.CaseResult> vector,
                              List<RetrievalBenchmark.CaseResult> bm25,
                              List<RetrievalBenchmark.CaseResult> rrf,
                              List<Map<String, Object>> rankings) {}
}
