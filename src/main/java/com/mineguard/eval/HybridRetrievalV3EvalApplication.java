package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.EmbeddingProperties;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.config.RetrievalProperties;
import com.mineguard.rag.Evidence;
import com.mineguard.rag.KnowledgeDocument;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 扩展混合检索与独立扰动评测。
 *
 * <p>主对照集和鲁棒性集共享同一次 BGE、Milvus、Lucene 索引，但分别计分；评测前通过
 * manifest 固定查询、语料、实现和参数，报告保留三路完整排名以及 RRF 相对向量通道的
 * 挽救与损伤样本。</p>
 */
public final class HybridRetrievalV3EvalApplication {
    static final Path EXPANDED_CASES = Path.of("data/eval/hybrid_retrieval_v3/expanded_cases.json");
    static final Path ROBUSTNESS_CASES = Path.of("data/eval/hybrid_retrieval_v3/robustness_cases.json");
    private static final Path EXTRA_KNOWLEDGE = Path.of("data/eval/hybrid_retrieval_v3/knowledge");
    private static final Path MANIFEST = Path.of("data/eval/hybrid_retrieval_v3_manifest.json");
    private static final Path ARCHIVE = Path.of("docs/eval/hybrid-retrieval-v3/report.json");
    private static final String MODEL = "BAAI/bge-small-zh-v1.5";
    private static final String REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781";
    private static final String PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final String MILVUS = "http://127.0.0.1:19540";
    private static final int DIMENSIONS = 512;
    private static final int CANDIDATE_K = 40;
    private static final int RRF_K = 60;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HybridRetrievalV3EvalApplication() {}

    public static void main(String[] args) throws Exception {
        KnowledgeLoader loader = evaluationLoader();
        List<KnowledgeDocument> documents = loader.load();
        List<EvalCase> expanded = readCases(EXPANDED_CASES);
        List<EvalCase> robustness = readCases(ROBUSTNESS_CASES);
        validateDatasets(expanded, robustness, documents);
        Map<String, Object> inputs = snapshot();
        if (args.length == 1 && "--freeze".equals(args[0])) {
            Files.writeString(MANIFEST, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("createdAt", Instant.now().toString(), "inputs", inputs)), StandardOpenOption.CREATE_NEW);
            System.out.println("v3 扩展检索集、独立鲁棒性集、语料、实现与参数已冻结；尚未执行评测。");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("只支持 --freeze 或无参数评测");
        JsonNode manifest = MAPPER.readTree(MANIFEST.toFile());
        if (!manifest.path("inputs").equals(MAPPER.valueToTree(inputs))) {
            throw new IllegalStateException("v3 混合检索冻结输入已变化");
        }
        JsonNode modelMetadata = requireEmbeddingService();

        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        String collection = "mineguard_hybrid_v3_" + UUID.randomUUID().toString().replace("-", "");
        Path directory = Path.of("data/runtime/hybrid-retrieval-v3", runId);
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("startedAt", Instant.now().toString());
        report.put("manifest", manifest);
        report.put("modelProvenance", modelMetadata);
        report.put("protocol", "80 条扩展主对照与 16 组、每组 4 种表达的 64 条独立扰动查询；冻结后首次执行，不以本次结果反向修改数据或参数");
        report.put("environment", "本地 CPU BGE INT8；Milvus 2.5 随机隔离集合；Lucene 10.3.1 BM25；等权 RRF(k=60)");
        boolean created = false;
        try {
            createCollection(collection);
            created = true;
            var embedding = new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties("openai-compatible",
                    "http://127.0.0.1:18082/v1", "", MODEL, DIMENSIONS, 180, 200, PREFIX), MAPPER);
            var store = new MilvusVectorStore(MILVUS, MAPPER, collection);
            try (var bm25 = new LuceneBm25Index(); var executor = Executors.newFixedThreadPool(2)) {
                var retriever = new KnowledgeRetriever(loader, embedding, store, bm25, executor,
                        new RetrievalProperties("hybrid", CANDIDATE_K, RRF_K));
                retriever.index();
                SuiteEvaluation expandedResult = evaluate(retriever, expanded);
                SuiteEvaluation robustnessResult = evaluate(retriever, robustness);
                report.put("indexedDocuments", documents.size());
                report.put("indexedChunks", retriever.indexedChunkCount());
                report.put("bm25IndexedChunks", retriever.bm25IndexedChunkCount());
                report.put("embeddingRequests", embedding.requestCount());
                report.put("expanded", suiteReport(expandedResult, expanded, false));
                report.put("robustness", suiteReport(robustnessResult, robustness, true));
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
                Files.createDirectories(ARCHIVE.getParent());
                writer.writeValue(ARCHIVE.toFile(), report);
            }
            System.out.println("v3 混合检索与鲁棒性报告：" + directory.toAbsolutePath());
        }
    }

    static void validateDatasets(List<EvalCase> expanded, List<EvalCase> robustness,
                                 List<KnowledgeDocument> documents) {
        Set<String> available = documents.stream().map(KnowledgeDocument::documentId).collect(Collectors.toSet());
        validateRows(expanded, available, 80, "扩展主对照集");
        validateRows(robustness, available, 64, "独立鲁棒性集");
        assertCounts(expanded, EvalCase::category,
                Map.of("SEMANTIC", 20L, "EXACT_IDENTIFIER", 30L, "MIXED", 30L), "扩展集类别");
        assertCounts(robustness, EvalCase::category,
                Map.of("SEMANTIC", 32L, "IDENTIFIER_MIXED", 32L), "鲁棒性类别");
        assertCounts(robustness, EvalCase::variantType,
                Map.of("BASE", 16L, "PARAPHRASE", 16L, "TERM_VARIATION", 16L, "DISTRACTOR_NOISE", 16L),
                "鲁棒性变体");
        Set<String> expandedQueries = expanded.stream().map(EvalCase::query).collect(Collectors.toSet());
        if (robustness.stream().map(EvalCase::query).anyMatch(expandedQueries::contains)) {
            throw new IllegalArgumentException("鲁棒性查询不得复用扩展主对照查询");
        }
        Map<String, List<EvalCase>> groups = robustness.stream().collect(Collectors.groupingBy(
                EvalCase::groupId, TreeMap::new, Collectors.toList()));
        if (groups.size() != 16 || groups.values().stream().anyMatch(items -> items.size() != 4)) {
            throw new IllegalArgumentException("鲁棒性集必须包含 16 个四变体意图组");
        }
        for (var entry : groups.entrySet()) {
            Set<List<String>> labels = entry.getValue().stream().map(EvalCase::expectedDocumentIds)
                    .map(List::copyOf).collect(Collectors.toSet());
            Set<String> categories = entry.getValue().stream().map(EvalCase::category).collect(Collectors.toSet());
            Set<String> variants = entry.getValue().stream().map(EvalCase::variantType).collect(Collectors.toSet());
            if (labels.size() != 1 || categories.size() != 1 || variants.size() != 4) {
                throw new IllegalArgumentException("鲁棒性意图组标注或变体不一致：" + entry.getKey());
            }
        }
    }

    private static void validateRows(List<EvalCase> cases, Set<String> available, int expectedSize, String name) {
        if (cases.size() != expectedSize) throw new IllegalArgumentException(name + "必须包含 " + expectedSize + " 条查询");
        Set<String> ids = new HashSet<>();
        Set<String> queries = new HashSet<>();
        for (EvalCase item : cases) {
            if (blank(item.id()) || blank(item.category()) || blank(item.query()) || !ids.add(item.id())
                    || !queries.add(item.query()) || item.expectedDocumentIds() == null
                    || item.expectedDocumentIds().isEmpty() || new LinkedHashSet<>(item.expectedDocumentIds()).size()
                    != item.expectedDocumentIds().size() || !available.containsAll(item.expectedDocumentIds())) {
                throw new IllegalArgumentException(name + "存在空值、重复项或无效文档标注：" + item.id());
            }
        }
    }

    private static void assertCounts(List<EvalCase> cases, Function<EvalCase, String> classifier,
                                     Map<String, Long> expected, String name) {
        Map<String, Long> actual = cases.stream().collect(Collectors.groupingBy(classifier, TreeMap::new, Collectors.counting()));
        if (!actual.equals(new TreeMap<>(expected))) throw new IllegalArgumentException(name + "数量不符合冻结设计：" + actual);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static SuiteEvaluation evaluate(KnowledgeRetriever retriever, List<EvalCase> cases) {
        List<RetrievalBenchmark.CaseResult> vector = new ArrayList<>();
        List<RetrievalBenchmark.CaseResult> bm25 = new ArrayList<>();
        List<RetrievalBenchmark.CaseResult> rrf = new ArrayList<>();
        List<Map<String, Object>> rankings = new ArrayList<>();
        for (EvalCase item : cases) {
            var result = retriever.retrieveDetailed(item.query(), 5);
            List<String> vectorIds = documentIds(result.vector());
            List<String> bm25Ids = documentIds(result.bm25());
            List<String> rrfIds = documentIds(result.fused());
            vector.add(RetrievalBenchmark.score(item.id(), item.query(), item.expectedDocumentIds(), vectorIds));
            bm25.add(RetrievalBenchmark.score(item.id(), item.query(), item.expectedDocumentIds(), bm25Ids));
            rrf.add(RetrievalBenchmark.score(item.id(), item.query(), item.expectedDocumentIds(), rrfIds));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("category", item.category());
            if (!blank(item.groupId())) row.put("groupId", item.groupId());
            if (!blank(item.variantType())) row.put("variantType", item.variantType());
            row.put("expectedDocumentIds", item.expectedDocumentIds());
            row.put("vector", vectorIds);
            row.put("bm25", bm25Ids);
            row.put("rrf", rrfIds);
            rankings.add(row);
        }
        return new SuiteEvaluation(vector, bm25, rrf, rankings);
    }

    private static Map<String, Object> suiteReport(SuiteEvaluation evaluation, List<EvalCase> cases,
                                                   boolean includeRobustness) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseCount", cases.size());
        result.put("categoryCounts", counts(cases, EvalCase::category));
        result.put("vector", RetrievalBenchmark.summarize(evaluation.vector()));
        result.put("bm25", RetrievalBenchmark.summarize(evaluation.bm25()));
        result.put("rrf", RetrievalBenchmark.summarize(evaluation.rrf()));
        result.put("perCategory", slicedMetrics(evaluation, cases, EvalCase::category));
        result.put("rrfComparedWithVector", pairedComparison(evaluation.vector(), evaluation.rrf()));
        result.put("rrfComparedWithBm25", pairedComparison(evaluation.bm25(), evaluation.rrf()));
        if (includeRobustness) {
            result.put("perVariant", slicedMetrics(evaluation, cases, EvalCase::variantType));
            result.put("vectorRobustness", robustnessSummary(evaluation.vector(), cases));
            result.put("bm25Robustness", robustnessSummary(evaluation.bm25(), cases));
            result.put("rrfRobustness", robustnessSummary(evaluation.rrf(), cases));
        }
        result.put("rankings", evaluation.rankings());
        return result;
    }

    private static Map<String, Object> slicedMetrics(SuiteEvaluation evaluation, List<EvalCase> cases,
                                                     Function<EvalCase, String> classifier) {
        Map<String, Object> result = new TreeMap<>();
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < cases.size(); i++) indexById.put(cases.get(i).id(), i);
        Map<String, List<EvalCase>> slices = cases.stream().collect(Collectors.groupingBy(
                classifier, TreeMap::new, Collectors.toList()));
        for (var entry : slices.entrySet()) {
            List<Integer> indexes = entry.getValue().stream().map(item -> indexById.get(item.id())).toList();
            result.put(entry.getKey(), Map.of(
                    "vector", RetrievalBenchmark.summarize(select(evaluation.vector(), indexes)),
                    "bm25", RetrievalBenchmark.summarize(select(evaluation.bm25(), indexes)),
                    "rrf", RetrievalBenchmark.summarize(select(evaluation.rrf(), indexes))));
        }
        return result;
    }

    private static List<RetrievalBenchmark.CaseResult> select(List<RetrievalBenchmark.CaseResult> source,
                                                               List<Integer> indexes) {
        return indexes.stream().map(source::get).toList();
    }

    static Map<String, Object> pairedComparison(List<RetrievalBenchmark.CaseResult> baseline,
                                                List<RetrievalBenchmark.CaseResult> candidate) {
        if (baseline.size() != candidate.size()) throw new IllegalArgumentException("配对评测样本数量不一致");
        int recallImproved = 0, recallDegraded = 0, recallEqual = 0;
        int mrrImproved = 0, mrrDegraded = 0, baselineMissCandidateHit = 0, baselineHitCandidateMiss = 0;
        double recallDelta = 0, mrrDelta = 0, ndcgDelta = 0;
        List<String> rescued = new ArrayList<>(), harmed = new ArrayList<>();
        for (int i = 0; i < baseline.size(); i++) {
            var left = baseline.get(i);
            var right = candidate.get(i);
            if (!left.id().equals(right.id())) throw new IllegalArgumentException("配对评测样本顺序不一致");
            double currentRecallDelta = right.recallAt5() - left.recallAt5();
            double currentMrrDelta = right.reciprocalRankAt5() - left.reciprocalRankAt5();
            recallDelta += currentRecallDelta;
            mrrDelta += currentMrrDelta;
            ndcgDelta += right.ndcgAt5() - left.ndcgAt5();
            if (currentRecallDelta > 1e-12) {
                recallImproved++;
                rescued.add(left.id());
            } else if (currentRecallDelta < -1e-12) {
                recallDegraded++;
                harmed.add(left.id());
            } else recallEqual++;
            if (currentMrrDelta > 1e-12) mrrImproved++;
            else if (currentMrrDelta < -1e-12) mrrDegraded++;
            if (left.recallAt5() == 0 && right.recallAt5() > 0) baselineMissCandidateHit++;
            if (left.recallAt5() > 0 && right.recallAt5() == 0) baselineHitCandidateMiss++;
        }
        int count = baseline.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseCount", count);
        result.put("recallAt5ImprovedCases", recallImproved);
        result.put("recallAt5DegradedCases", recallDegraded);
        result.put("recallAt5EqualCases", recallEqual);
        result.put("mrrAt5ImprovedCases", mrrImproved);
        result.put("mrrAt5DegradedCases", mrrDegraded);
        result.put("baselineMissCandidateHitCases", baselineMissCandidateHit);
        result.put("baselineHitCandidateMissCases", baselineHitCandidateMiss);
        result.put("meanRecallAt5Delta", recallDelta / count);
        result.put("meanMrrAt5Delta", mrrDelta / count);
        result.put("meanNdcgAt5Delta", ndcgDelta / count);
        result.put("improvedCaseIds", rescued);
        result.put("degradedCaseIds", harmed);
        return result;
    }

    static Map<String, Object> robustnessSummary(List<RetrievalBenchmark.CaseResult> results, List<EvalCase> cases) {
        if (results.size() != cases.size()) throw new IllegalArgumentException("鲁棒性结果与样本数量不一致");
        Map<String, RetrievalBenchmark.CaseResult> byId = results.stream().collect(Collectors.toMap(
                RetrievalBenchmark.CaseResult::id, Function.identity()));
        Map<String, List<EvalCase>> groups = cases.stream().collect(Collectors.groupingBy(
                EvalCase::groupId, TreeMap::new, Collectors.toList()));
        int allVariantsHit = 0, allVariantsFullRecall = 0;
        double worstRecallSum = 0, rankRangeSum = 0, perturbedRecallDeltaSum = 0;
        int perturbedCount = 0;
        List<Map<String, Object>> groupDetails = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<RetrievalBenchmark.CaseResult> groupResults = entry.getValue().stream()
                    .map(item -> byId.get(item.id())).toList();
            double worstRecall = groupResults.stream().mapToDouble(RetrievalBenchmark.CaseResult::recallAt5).min().orElseThrow();
            boolean allHit = groupResults.stream().allMatch(item -> item.recallAt5() > 0);
            boolean allFullRecall = groupResults.stream().allMatch(item -> item.recallAt5() == 1);
            if (allHit) allVariantsHit++;
            if (allFullRecall) allVariantsFullRecall++;
            worstRecallSum += worstRecall;
            int minRank = groupResults.stream().mapToInt(HybridRetrievalV3EvalApplication::firstRelevantRank).min().orElseThrow();
            int maxRank = groupResults.stream().mapToInt(HybridRetrievalV3EvalApplication::firstRelevantRank).max().orElseThrow();
            rankRangeSum += maxRank - minRank;
            EvalCase base = entry.getValue().stream().filter(item -> "BASE".equals(item.variantType())).findFirst().orElseThrow();
            double baseRecall = byId.get(base.id()).recallAt5();
            for (EvalCase item : entry.getValue()) if (!"BASE".equals(item.variantType())) {
                perturbedRecallDeltaSum += byId.get(item.id()).recallAt5() - baseRecall;
                perturbedCount++;
            }
            groupDetails.add(Map.of("groupId", entry.getKey(), "allVariantsHitAt5", allHit,
                    "allVariantsFullRecallAt5", allFullRecall, "worstVariantRecallAt5", worstRecall,
                    "firstRelevantRankRange", maxRank - minRank));
        }
        int groupCount = groups.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("groupCount", groupCount);
        summary.put("allVariantsHitAt5Rate", allVariantsHit / (double) groupCount);
        summary.put("allVariantsFullRecallAt5Rate", allVariantsFullRecall / (double) groupCount);
        summary.put("meanWorstVariantRecallAt5", worstRecallSum / groupCount);
        summary.put("meanFirstRelevantRankRange", rankRangeSum / groupCount);
        summary.put("meanPerturbedRecallAt5DeltaFromBase", perturbedRecallDeltaSum / perturbedCount);
        summary.put("groups", groupDetails);
        return summary;
    }

    private static int firstRelevantRank(RetrievalBenchmark.CaseResult item) {
        if (item.reciprocalRankAt5() == 0) return 6;
        return (int) Math.round(1d / item.reciprocalRankAt5());
    }

    private static Map<String, Long> counts(List<EvalCase> cases, Function<EvalCase, String> classifier) {
        return cases.stream().collect(Collectors.groupingBy(classifier, TreeMap::new, Collectors.counting()));
    }

    private static List<String> documentIds(List<KnowledgeRetriever.RankedEvidence> ranking) {
        return ranking.stream().map(KnowledgeRetriever.RankedEvidence::evidence).map(Evidence::documentId)
                .distinct().limit(5).toList();
    }

    private static List<EvalCase> readCases(Path path) throws Exception {
        return MAPPER.readValue(path.toFile(), new TypeReference<>() {});
    }

    private static KnowledgeLoader evaluationLoader() {
        return new CompositeKnowledgeLoader(
                loader("data/knowledge"), loader(EXTRA_KNOWLEDGE.toString()));
    }

    private static KnowledgeLoader loader(String path) {
        return new KnowledgeLoader(new MineGuardProperties(null, null, path, "", 1));
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
            String message = result == null ? "空响应" : result.path("message").asText("未提供原因").replaceAll("[\\r\\n]", " ");
            if (message.length() > 240) message = message.substring(0, 240);
            throw new IllegalStateException("Milvus " + endpoint + " 未成功，code="
                    + (result == null ? -1 : result.path("code").asInt(-1)) + "，原因=" + message);
        }
        return result;
    }

    private static Map<String, Object> snapshot() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        List<Path> paths = new ArrayList<>(List.of(EXPANDED_CASES, ROBUSTNESS_CASES, Path.of("pom.xml"),
                Path.of("src/main/java/com/mineguard/eval/HybridRetrievalV3EvalApplication.java"),
                Path.of("src/main/java/com/mineguard/eval/RetrievalBenchmark.java"),
                Path.of("src/main/java/com/mineguard/rag/KnowledgeRetriever.java"),
                Path.of("src/main/java/com/mineguard/rag/LuceneBm25Index.java"),
                Path.of("src/main/java/com/mineguard/rag/MilvusVectorStore.java"),
                Path.of("src/main/java/com/mineguard/rag/OpenAiCompatibleEmbeddingClient.java"),
                Path.of("scripts/embedding/server.py"), Path.of("scripts/embedding/requirements.txt")));
        for (Path directory : List.of(Path.of("data/knowledge"), EXTRA_KNOWLEDGE)) {
            try (var files = Files.list(directory)) {
                paths.addAll(files.filter(path -> path.toString().endsWith(".md")).sorted().toList());
            }
        }
        for (Path path : paths) hashes.put(path.toString().replace('\\', '/'), HoldoutGuard.textHash(path));
        return Map.ofEntries(
                Map.entry("suite", "hybrid-retrieval-and-robustness-v3"),
                Map.entry("expandedCaseCount", 80), Map.entry("robustnessCaseCount", 64),
                Map.entry("robustnessGroupCount", 16), Map.entry("documentCount", 40),
                Map.entry("model", MODEL), Map.entry("revision", REVISION), Map.entry("dimensions", DIMENSIONS),
                Map.entry("queryPrefix", PREFIX), Map.entry("quantization", "INT8"),
                Map.entry("vectorStore", "Milvus 2.5/COSINE/AUTOINDEX/Strong"),
                Map.entry("bm25", "Lucene 10.3.1/CJKAnalyzer/BM25Similarity"),
                Map.entry("fusion", "equal-weight RRF"), Map.entry("candidateK", CANDIDATE_K),
                Map.entry("rrfK", RRF_K), Map.entry("hashes", hashes));
    }

    record EvalCase(String id, String category, String groupId, String variantType, String query,
                    List<String> expectedDocumentIds) {}

    private record SuiteEvaluation(List<RetrievalBenchmark.CaseResult> vector,
                                   List<RetrievalBenchmark.CaseResult> bm25,
                                   List<RetrievalBenchmark.CaseResult> rrf,
                                   List<Map<String, Object>> rankings) {}

    private static final class CompositeKnowledgeLoader extends KnowledgeLoader {
        private final KnowledgeLoader extra;

        private CompositeKnowledgeLoader(KnowledgeLoader base, KnowledgeLoader extra) {
            super(new MineGuardProperties(null, null, "data/knowledge", "", 1));
            this.extra = extra;
        }

        @Override
        public List<KnowledgeDocument> load() {
            List<KnowledgeDocument> combined = new ArrayList<>(super.load());
            combined.addAll(extra.load());
            combined.sort(Comparator.comparing(KnowledgeDocument::documentId));
            if (combined.stream().map(KnowledgeDocument::documentId).distinct().count() != combined.size()) {
                throw new IllegalStateException("扩展评测知识文档 ID 重复");
            }
            return List.copyOf(combined);
        }
    }
}
