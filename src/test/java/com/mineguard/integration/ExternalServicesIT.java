package com.mineguard.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.event.EventType;
import com.mineguard.event.JdbcSafetyEventRepository;
import com.mineguard.event.SafetyEventFilter;
import com.mineguard.config.DemoDataSeeder;
import com.mineguard.rag.DocumentChunk;
import com.mineguard.rag.MilvusVectorStore;
import com.mineguard.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 只连接 compose.integration.yml 的专用本机端口，不接受生产地址覆盖。 */
@EnabledIfEnvironmentVariable(named = "MINEGUARD_RUN_EXTERNAL_IT", matches = "true")
class ExternalServicesIT {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String MILVUS = "http://127.0.0.1:19540";

    @Test void postgresPersistsSeedAndExecutesRealSql() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:15432/mineguard_test", "mineguard_test", "local-test-only");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        var repository = new JdbcSafetyEventRepository(new JdbcTemplate(dataSource));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(new DemoDataSeeder(repository), "seed");
        assertThat(repository.count()).isEqualTo(420);
        var filter = new SafetyEventFilter("3号采区", EventType.GAS_WARNING,
                DemoDataSeeder.EVENT_ANCHOR.minus(Duration.ofDays(7)), DemoDataSeeder.EVENT_ANCHOR, null);
        var events = repository.find(filter);
        assertThat(events).isNotEmpty().allMatch(e -> e.area().equals("3号采区") && e.eventType() == EventType.GAS_WARNING);
        assertThat(repository.aggregate(filter).get("GAS_WARNING")).isEqualTo((long) events.size());
        // 重新创建仓储连接，验证数据来自数据库，不是 Java 对象内存。
        var another = new JdbcSafetyEventRepository(new JdbcTemplate(dataSource));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(new DemoDataSeeder(another), "seed");
        assertThat(another.count()).isEqualTo(420);
    }

    @Test void milvusCreatesSchemaUpsertsReplacesAndReadsThroughNewClient() throws Exception {
        String collection = "mineguard_it_" + UUID.randomUUID().toString().replace("-", "");
        var fields = new ArrayList<Map<String, Object>>();
        fields.add(Map.of("fieldName", "id", "dataType", "VarChar", "isPrimary", true, "elementTypeParams", Map.of("max_length", "512")));
        for (String field : List.of("documentId", "title", "chunkId", "content")) {
            fields.add(Map.of("fieldName", field, "dataType", "VarChar", "elementTypeParams", Map.of("max_length", "65535")));
        }
        fields.add(Map.of("fieldName", "vector", "dataType", "FloatVector", "elementTypeParams", Map.of("dim", "768")));
        post("collections/create", Map.of("collectionName", collection,
                "schema", Map.of("autoId", false, "enabledDynamicField", false, "fields", fields),
                "indexParams", List.of(Map.of("fieldName", "vector", "indexName", "vector_idx", "indexType", "AUTOINDEX", "metricType", "COSINE")),
                "params", Map.of("consistencyLevel", "Strong")));
        try {
            var store = new MilvusVectorStore(MILVUS, mapper, collection);
            var a = entry("a", "安全帽原文", 0);
            var b = entry("b", "瓦斯原文", 1);
            store.replaceAll(List.of(a, b));
            assertThat(store.search(a.vector(), 2)).hasSize(2);
            store.replaceAll(List.of(entry("a", "安全帽修订", 0), b));
            var fresh = new MilvusVectorStore(MILVUS, mapper, collection);
            assertThat(fresh.search(a.vector(), 2)).hasSize(2).first().satisfies(match -> {
                assertThat(match.chunk().chunkId()).isEqualTo("a");
                assertThat(match.chunk().content()).isEqualTo("安全帽修订");
            });
            store.replaceAll(List.of(b));
            assertThat(store.search(b.vector(), 10)).hasSize(1).first().satisfies(match -> assertThat(match.chunk().chunkId()).isEqualTo("b"));
            store.replaceAll(List.of());
            assertThat(store.search(b.vector(), 10)).isEmpty();
        } finally {
            // 只清理本测试刚创建的随机集合，不删除用户集合或 Docker 数据卷。
            post("collections/drop", Map.of("collectionName", collection));
        }
    }

    private VectorStore.VectorEntry entry(String id, String text, int dimension) {
        float[] vector = new float[768];
        vector[dimension] = 1;
        return new VectorStore.VectorEntry(new DocumentChunk("doc-" + id, "测试文档", id, text), vector);
    }

    private JsonNode post(String endpoint, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(MILVUS + "/v2/vectordb/" + endpoint)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var result = mapper.readTree(response.body());
        assertThat(result.path("code").asInt(-1)).as("Milvus 接口 %s", endpoint).isZero();
        return result;
    }
}
