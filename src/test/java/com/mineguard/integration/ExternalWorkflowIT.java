package com.mineguard.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.contract.IndustrialContractServer;
import com.mineguard.device.HttpIndustrialGateway;
import com.mineguard.llm.DeterministicAgentModelClient;
import com.mineguard.security.*;
import com.mineguard.workflow.*;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** 独立 JVM + 外部 PostgreSQL 的故障注入验收；模型使用本地桩，工业侧为本地 HTTP 契约服务。 */
@EnabledIfEnvironmentVariable(named = "MINEGUARD_RUN_EXTERNAL_IT", matches = "true")
class ExternalWorkflowIT {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Node> nodes = new ArrayList<>();
    private final String fixturePassword = "Local-IT-" + UUID.randomUUID();
    private final String contractToken = UUID.randomUUID().toString();
    private final String schema = "workflow_it_" + UUID.randomUUID().toString().replace("-", "");
    private final Path output = Path.of("data/runtime/distributed-it", schema).toAbsolutePath();
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private String databaseUrl;
    private int modelPort, contractPort;

    @Test void processCrashRestartApprovalReplayAndReceiverReceipts() throws Exception {
        Files.createDirectories(output);
        var adminJdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:15432/mineguard_test", "mineguard_test", "local-test-only"));
        // 测试只拥有本次 UUID 生成的独立 schema，不迁移或删除用户表。
        adminJdbc.execute("CREATE SCHEMA " + schema);
        databaseUrl = "jdbc:postgresql://127.0.0.1:15432/mineguard_test?currentSchema=" + schema;
        source = new DriverManagerDataSource(databaseUrl, "mineguard_test", "local-test-only");
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema(schema).load().migrate();
        var contractJdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        var blockNext = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor(); model.setExecutor(modelExecutor);
        model.createContext("/chat/completions", exchange -> {
            try {
                var request = mapper.readTree(exchange.getRequestBody());
                if (blockNext.compareAndSet(true, false)) { entered.countDown(); release.await(90, TimeUnit.SECONDS); }
                var user = mapper.readTree(request.path("messages").path(1).path("content").asText());
                String plan = new DeterministicAgentModelClient(mapper).createPlan(user.path("query").asText(), List.of(), null);
                byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", plan))),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20)));
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        model.start(); modelPort = model.getAddress().getPort();
        try (var contract = new IndustrialContractServer(contractJdbc, mapper, contractToken, 0)) {
            contract.start(); contractPort = contract.port();
            Node a = start("node-a");
            String admin = login(a, "it-admin");
            var operator = call(a, "/api/admin/users", admin, Map.of("username", "it-operator", "password", fixturePassword, "roles", List.of("OPERATOR")), "operator");
            var approver = call(a, "/api/admin/users", admin, Map.of("username", "it-approver", "password", fixturePassword, "roles", List.of("APPROVER")), "approver");
            String op = login(a, "it-operator"), reviewer = login(a, "it-approver");
            blockNext.set(true);
            String first = create(a, op, "查询安全帽规范", "planning-crash");
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(task(a, op, first).path("state").asText()).isEqualTo("PLANNING");
            Node b = start("node-b");
            Instant crashedAt = Instant.now(); kill(a);
            JsonNode recovered = awaitState(b, op, first, "COMPLETED");
            long recoveryMs = Duration.between(crashedAt, Instant.now()).toMillis();
            assertThat(jdbc.queryForObject("SELECT fence FROM agent_task WHERE task_id=?", Long.class, first)).isGreaterThanOrEqualTo(2);
            assertThat(recovered.path("evidence").size()).isPositive(); release.countDown();

            // 所有应用进程退出后，待审批任务、凭据和事件仍保存在 PostgreSQL。
            String high = create(b, op, "启动 camera-03 的 intrusion_detection 检测任务", "durable-approval");
            String planHash = awaitState(b, op, high, "WAITING_APPROVAL").path("planHash").asText();
            long before = contract.operationCount(); kill(b);
            Node c = start("node-c"), d = start("node-d");
            assertThat(task(c, op, high).path("state").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(contract.operationCount()).isEqualTo(before);
            var decision = Map.of("reason", "独立审批员确认本地白名单目标", "planHash", planHash);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> call(c, "/api/tasks/" + high + "/approve", reviewer, decision, "one-approval"));
                var two = pool.submit(() -> call(d, "/api/tasks/" + high + "/approve", reviewer, decision, "one-approval"));
                one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
            }
            assertThat(awaitState(d, op, high, "COMPLETED").path("result").path("verification").size()).isEqualTo(1);
            assertThat(contract.operationCount()).isEqualTo(before + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_decision WHERE task_id=?", Long.class, high)).isEqualTo(1);
            var events = call(d, "/api/agent/tasks/" + high + "/events", op, null, null);
            List<Long> sequences = new ArrayList<>(); events.forEach(event -> sequences.add(event.path("sequence").asLong()));
            assertThat(sequences).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, events.size()).boxed().toList());
            var sse = http.send(HttpRequest.newBuilder(URI.create(c.url() + "/api/agent/tasks/" + high + "/stream"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + op).header("Last-Event-ID", "2").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(sse.statusCode()).isEqualTo(200);
            List<Long> replay = sse.body().lines().filter(line -> line.startsWith("id:")).map(line -> Long.valueOf(line.substring(3).trim())).toList();
            assertThat(replay).containsExactlyElementsOf(sequences.stream().filter(n -> n > 2).toList());
            kill(c); kill(d);

            // 精确注入“工业提交成功、应用检查点未写”的故障窗口。
            var store = new JdbcAgentTaskStore(jdbc, mapper, new DataSourceTransactionManager(source));
            Actor owner = mapper.convertValue(operator, Actor.class), reviewerActor = mapper.convertValue(approver, Actor.class);
            AgentTask receiptTask = prepareInterruptedWrite(store, owner, reviewerActor, "有接收端回执");
            AgentTask unknownTask = prepareInterruptedWrite(store, owner, reviewerActor, "没有接收端回执");
            var gateway = new HttpIndustrialGateway("http://127.0.0.1:" + contractPort, contractToken,
                    "camera-03,camera-08,camera-17", "intrusion_detection,no_helmet,personnel_violation", mapper);
            gateway.startDetectionTask("camera-17", "personnel_violation", receiptTask.getTaskId() + ":write");
            long committedCommands = contract.operationCount();
            Node e = start("node-e");
            awaitState(e, op, receiptTask.getTaskId(), "COMPLETED");
            awaitState(e, op, unknownTask.getTaskId(), "RECOVERY_REQUIRED");
            assertThat(contract.operationCount()).isEqualTo(committedCommands);
            assertThat(store.history(receiptTask.getTaskId(), 0, 500)).anyMatch(event -> Boolean.TRUE.equals(event.payload().get("recoveredFromReceipt")));
            assertThat(store.step(unknownTask.getTaskId(), "write").orElseThrow().status()).isEqualTo("STARTED");
            var evidence = Map.of("status", "PASSED", "database", "PostgreSQL", "applicationJvmCount", nodes.size(),
                    "planningCrashRecoveryMs", recoveryMs, "eventCount", events.size(), "sseReplayCount", replay.size(),
                    "duplicateCommandsDuringRecovery", 0, "model", "本地确定性 HTTP 桩，不是 DeepSeek",
                    "industrial", "本地 HTTP 契约服务，不连接物理设备", "finishedAt", Instant.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(), evidence);
            System.out.println("多进程 PostgreSQL 验收通过，证据目录：" + output);
        } finally {
            release.countDown(); nodes.forEach(this::kill); model.stop(0); modelExecutor.shutdownNow();
            if (!schema.matches("workflow_it_[a-f0-9]{32}")) throw new IllegalStateException("清理目标校验失败");
            adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private AgentTask prepareInterruptedWrite(JdbcAgentTaskStore store, Actor owner, Actor reviewer, String query) {
        AgentTask task = store.create(query, owner, UUID.randomUUID().toString());
        var lease = store.claim(task.getTaskId(), "crash-injection", 60).orElseThrow();
        PlanStep step = new PlanStep("write", AgentStepType.START_DETECTION_TASK, "本地故障窗口测试", Map.of("cameraId", "camera-17", "algorithm", "personnel_violation"));
        task.transitionTo(AgentTaskState.PLANNING); task.setPlan(new AgentPlan("start_detection", RiskLevel.HIGH, List.of(step)));
        task.setPlanHash(Digests.canonical(mapper, task.getPlan()));
        task.transitionTo(AgentTaskState.RETRIEVING); task.transitionTo(AgentTaskState.ANALYZING); task.transitionTo(AgentTaskState.WAITING_APPROVAL);
        store.checkpoint(task, lease, List.of()); store.release(lease);
        task = store.decide(task.getTaskId(), reviewer, "fixture-approval", "APPROVED", "故障测试授权", task.getPlanHash(), 600, t -> new TaskAccessPolicy().requireApproval(reviewer, t));
        lease = store.claim(task.getTaskId(), "crash-injection", 60).orElseThrow();
        store.beginStep(task, lease, step.id(), Digests.canonical(mapper, step), true, new JdbcAgentTaskStore.EventDraft(TaskEventType.TOOL_STARTED, Map.of("stepId", step.id())));
        jdbc.update("UPDATE agent_task SET lease_until=? WHERE task_id=?", java.sql.Timestamp.from(store.now().minusSeconds(1)), task.getTaskId());
        return task;
    }

    private Node start(String name) throws Exception {
        int port; try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        // 使用当前 JDK 的平台可执行文件，兼容本地 Windows 与 GitHub Linux runner。
        String javaExecutable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString(), "-jar", "target/mineguard-1.0.0-SNAPSHOT.jar",
                "--server.port=" + port, "--server.address=127.0.0.1", "--spring.datasource.url=" + databaseUrl,
                "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.flyway.default-schema=" + schema,
                "--mineguard.runtime.node-id=" + name, "--mineguard.runtime.lease-seconds=6", "--mineguard.runtime.scheduler-enabled=true",
                "--mineguard.llm.provider=openai-compatible", "--mineguard.llm.base-url=http://127.0.0.1:" + modelPort,
                "--mineguard.llm.model=local-test-stub", "--mineguard.llm.max-calls=50", "--mineguard.llm.request-timeout-seconds=90",
                "--mineguard.industrial.type=http-contract", "--mineguard.industrial.base-url=http://127.0.0.1:" + contractPort,
                "--mineguard.vector-store.type=in-memory", "--mineguard.embedding.provider=hashing", "--mineguard.knowledge-path=data/knowledge", "--mineguard.trace-path=" + output.resolve(name + "-traces"));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(name + ".log").toFile());
        builder.environment().putAll(Map.of("DATABASE_USERNAME", "mineguard_test", "DATABASE_PASSWORD", "local-test-only",
                "OPENAI_API_KEY", "offline-test-secret", "MINEGUARD_BOOTSTRAP_USERNAME", "it-admin", "MINEGUARD_BOOTSTRAP_PASSWORD", fixturePassword,
                "MINEGUARD_INDUSTRIAL_TOKEN", contractToken));
        Node node = new Node(builder.start(), port); nodes.add(node);
        long until = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < until) {
            if (!node.process().isAlive()) throw new AssertionError("子进程启动失败，检查 " + output.resolve(name + ".log"));
            try { if (http.send(HttpRequest.newBuilder(URI.create(node.url() + "/api/health")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return node; }
            catch (Exception ignored) { }
            Thread.sleep(200);
        }
        throw new AssertionError("子进程启动超时，检查 " + name);
    }
    private void kill(Node node) {
        if (!node.process().isAlive()) return;
        node.process().destroyForcibly();
        try { if (!node.process().waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("未能结束本测试子进程"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
    private String login(Node node, String username) throws Exception {
        return call(node, "/api/auth/login", null, Map.of("username", username, "password", fixturePassword), null).path("accessToken").asText();
    }
    private String create(Node node, String token, String query, String key) throws Exception {
        return call(node, "/api/agent/tasks", token, Map.of("query", query), key).path("taskId").asText();
    }
    private JsonNode task(Node node, String token, String id) throws Exception { return call(node, "/api/agent/tasks/" + id, token, null, null); }
    private JsonNode awaitState(Node node, String token, String id, String state) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(40).toNanos(); JsonNode last = null;
        while (System.nanoTime() < until) {
            last = task(node, token, id); if (last.path("state").asText().equals(state)) return last;
            if (Set.of("FAILED", "RECOVERY_REQUIRED").contains(last.path("state").asText())) break;
            Thread.sleep(100);
        }
        throw new AssertionError("任务未达到 " + state + "，当前状态=" + (last == null ? "UNKNOWN" : last.path("state").asText()) + "，任务=" + id);
    }
    private JsonNode call(Node node, String path, String token, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(node.url() + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))); else request.GET();
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("接口状态 %s", path).isEqualTo(200);
        return mapper.readTree(response.body());
    }
    private record Node(Process process, int port) { String url() { return "http://127.0.0.1:" + port; } }
}
