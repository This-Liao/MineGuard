package com.mineguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.EmbeddingProperties;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.rag.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 独立检索对照实验；不启动 Agent、不读取规划模型密钥、不连接业务数据库。 */
public final class SemanticRetrievalEvalApplication {
    private static final Path CASES = Path.of("data/eval/retrieval_holdout_v1.json");
    private static final Path MANIFEST = Path.of("data/eval/retrieval_v1_manifest.json");
    private static final String MODEL = "BAAI/bge-small-zh-v1.5";
    private static final String REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781";
    private static final String PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private SemanticRetrievalEvalApplication() {}

    public static void main(String[] args) throws Exception {
        KnowledgeLoader loader = new KnowledgeLoader(new MineGuardProperties(null, null, "data/knowledge", "", 1));
        validateCases(MAPPER.readTree(CASES.toFile()), loader.load());
        Map<String, Object> inputs = snapshot();
        if (args.length == 1 && "--freeze".equals(args[0])) {
            Files.writeString(MANIFEST, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("createdAt", Instant.now().toString(), "inputs", inputs)), StandardOpenOption.CREATE_NEW);
            System.out.println("检索用例、语料、评分器和模型配置已冻结；尚未调用向量服务。");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("只支持 --freeze 或无参数评测");
        JsonNode frozen = MAPPER.readTree(MANIFEST.toFile());
        if (!frozen.path("inputs").equals(MAPPER.valueToTree(inputs))) throw new IllegalStateException("检索冻结输入已变化");
        HttpResponse<String> health = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/health")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode metadata = MAPPER.readTree(health.body());
        if (health.statusCode() != 200 || !"UP".equals(metadata.path("status").asText())
                || !MODEL.equals(metadata.path("model").asText()) || !REVISION.equals(metadata.path("revision").asText())
                || metadata.path("dimensions").asInt() != 512 || !"INT8".equals(metadata.path("quantization").asText())) {
            throw new IllegalStateException("本地 BGE 服务与冻结配置不一致");
        }
        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        Path directory = Path.of("data/runtime/semantic-retrieval-eval", runId);
        Files.createDirectories(directory);
        // 初次试验不可自动重复；未来复现请保留原报告并建立有版本号的新批次。
        Files.writeString(directory.getParent().resolve("retrieval-v1-attempt.txt"), runId, StandardOpenOption.CREATE_NEW);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId); report.put("startedAt", Instant.now().toString());
        report.put("manifest", frozen); report.put("modelProvenance", metadata);
        report.put("environment", "本地 CPU 真实 BGE INT8 推理；20 篇合成知识文档；内存余弦检索；无 Rerank");
        report.put("protocol", "30 条开发者预先标注的新查询，一次对照；不是第三方盲测或生产语料评测");
        try {
            var semantic = new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties("openai-compatible",
                    "http://127.0.0.1:18082/v1", "", MODEL, 512, 60, 100, PREFIX), MAPPER);
            report.put("hashing", evaluate(loader, new HashingEmbeddingClient()));
            report.put("semantic", evaluate(loader, semantic));
            report.put("embeddingRequests", semantic.requestCount());
            report.put("status", "COMPLETED");
        } catch (Exception ex) {
            report.put("status", "ABORTED"); report.put("error", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), report);
            System.out.println("独立检索报告：" + directory.toAbsolutePath());
        }
    }

    private static Map<String, Object> evaluate(KnowledgeLoader loader, EmbeddingClient client) throws Exception {
        long start = System.nanoTime();
        KnowledgeRetriever retriever = new KnowledgeRetriever(loader, client, new InMemoryVectorStore());
        retriever.index();
        var result = RetrievalBenchmark.evaluate(retriever, CASES, MAPPER);
        return Map.of("dimensions", client.dimensions(), "indexedChunks", retriever.indexedChunkCount(),
                "elapsedMs", (System.nanoTime() - start) / 1_000_000, "metrics", result);
    }

    static void validateCases(JsonNode cases, List<KnowledgeDocument> documents) {
        Set<String> available = new HashSet<>(); documents.forEach(d -> available.add(d.documentId()));
        Set<String> ids = new HashSet<>(), queries = new HashSet<>();
        if (!cases.isArray() || cases.size() != 30) throw new IllegalArgumentException("独立检索集必须包含 30 条查询");
        for (JsonNode row : cases) {
            String id = row.path("id").asText(), query = row.path("query").asText();
            JsonNode expected = row.path("expectedDocumentIds");
            if (id.isBlank() || query.isBlank() || !ids.add(id) || !queries.add(query)
                    || !expected.isArray() || expected.isEmpty()) throw new IllegalArgumentException("检索标注无效或重复");
            Set<String> seen = new HashSet<>();
            for (JsonNode document : expected) if (!available.contains(document.asText()) || !seen.add(document.asText())) {
                throw new IllegalArgumentException("相关文档不存在或重复");
            }
        }
    }

    private static Map<String, Object> snapshot() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        List<Path> paths = new ArrayList<>(List.of(CASES,
                Path.of("src/main/java/com/mineguard/eval/SemanticRetrievalEvalApplication.java"),
                Path.of("src/main/java/com/mineguard/eval/RetrievalBenchmark.java"),
                Path.of("src/main/java/com/mineguard/rag/EmbeddingClient.java"),
                Path.of("src/main/java/com/mineguard/rag/HashingEmbeddingClient.java"),
                Path.of("src/main/java/com/mineguard/rag/OpenAiCompatibleEmbeddingClient.java"),
                Path.of("src/main/java/com/mineguard/rag/KnowledgeRetriever.java"),
                Path.of("src/main/java/com/mineguard/rag/KnowledgeLoader.java"),
                Path.of("src/main/java/com/mineguard/rag/InMemoryVectorStore.java"),
                Path.of("scripts/embedding/server.py"), Path.of("scripts/embedding/requirements.txt")));
        try (var files = Files.list(Path.of("data/knowledge"))) { paths.addAll(files.filter(p -> p.toString().endsWith(".md")).sorted().toList()); }
        for (Path path : paths) hashes.put(path.toString().replace('\\', '/'), HoldoutGuard.textHash(path));
        return Map.of("suite", "retrieval-holdout-v1", "caseCount", 30, "model", MODEL, "revision", REVISION,
                "dimensions", 512, "queryPrefix", PREFIX, "quantization", "INT8", "hashes", hashes);
    }
}
