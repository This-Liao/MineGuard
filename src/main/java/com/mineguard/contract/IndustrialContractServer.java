package com.mineguard.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.device.DetectionTask;
import com.mineguard.device.HttpIndustrialGateway;
import com.mineguard.security.Digests;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 可独立运行的本地工业 HTTP 契约服务；数据与幂等回执持久化，不控制物理设备。 */
public final class IndustrialContractServer implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean loseNextWriteResponse;

    public IndustrialContractServer(JdbcTemplate jdbc, ObjectMapper mapper, String token, int port) throws IOException {
        if (token == null || token.length() < 16) throw new IllegalArgumentException("必须配置至少 16 字符的契约服务凭据");
        this.jdbc = jdbc; this.mapper = mapper; this.token = token;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        jdbc.execute("CREATE TABLE IF NOT EXISTS contract_task(task_id VARCHAR(64) PRIMARY KEY,camera_id VARCHAR(64) NOT NULL,algorithm VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS contract_operation(operation_key VARCHAR(160) PRIMARY KEY,request_hash VARCHAR(64) NOT NULL,result TEXT NOT NULL,reply TEXT NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        seed("camera-03", "intrusion_detection", "STOPPED"); seed("camera-08", "no_helmet", "RUNNING"); seed("camera-17", "personnel_violation", "STOPPED");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle); server.setExecutor(executor);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public long operationCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM contract_operation", Long.class); }
    public void loseNextWriteResponse() { loseNextWriteResponse = true; }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void seed(String camera, String algorithm, String status) {
        try { jdbc.update("INSERT INTO contract_task VALUES (?,?,?,?,CURRENT_TIMESTAMP)", HttpIndustrialGateway.taskId(camera, algorithm), camera, algorithm, status); }
        catch (DuplicateKeyException ignored) { }
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getPath().equals("/health")) { reply(exchange, 200, Map.of("status", "UP", "environment", "LOCAL_CONTRACT")); return; }
            String supplied = exchange.getRequestHeaders().getFirst("X-Adapter-Key");
            if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) { reply(exchange, 401, Map.of("message", "凭据无效")); return; }
            if (!exchange.getRequestMethod().equals("POST")) { reply(exchange, 405, Map.of("message", "方法不支持")); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(65537);
            if (bytes.length > 65536) { reply(exchange, 413, Map.of("message", "请求过大")); return; }
            JsonNode body = mapper.readTree(bytes); String path = exchange.getRequestURI().getPath();
            Object result = switch (path) {
                case "/contract/device-status" -> {
                    String camera = body.path("camera_id").asText();
                    yield Map.of("status", camera.matches("camera-(0[1-9]|1[0-9]|2[0-4])") ? (camera.equals("camera-21") ? "OFFLINE" : camera.equals("camera-17") ? "DEGRADED" : "ONLINE") : "UNKNOWN");
                }
                case "/contract/tasks" -> tasks(body.path("camera_id").asText());
                case "/contract/operation" -> operation(body.path("operation_key").asText());
                case "/startTask", "/stopTask" -> command(path, body, exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                case "/check" -> {
                    var values = jdbc.queryForList("SELECT status FROM contract_task WHERE task_id=?", String.class, body.path("task_id").asText());
                    yield Map.of("task_id", body.path("task_id").asText(), "status", values.isEmpty() ? "任务不存在" : values.getFirst().equals("RUNNING") ? "任务已运行" : "任务已停止");
                }
                default -> throw new IllegalArgumentException("接口不存在");
            };
            if ((path.equals("/startTask") || path.equals("/stopTask")) && loseNextWriteResponse) {
                loseNextWriteResponse = false;
                reply(exchange, 503, Map.of("message", "测试故障：提交后的响应丢失"));
            } else reply(exchange, 200, result);
        } catch (IllegalStateException ex) { reply(exchange, 409, Map.of("message", "幂等键冲突")); }
        catch (Exception ex) { reply(exchange, 400, Map.of("message", "请求无效")); }
        finally { exchange.close(); }
    }

    private synchronized Object command(String path, JsonNode body, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,160}")) throw new IllegalArgumentException("缺少幂等键");
        String hash = Digests.canonical(mapper, Map.of("path", path, "body", body));
        return tx.execute(status -> {
            var previous = jdbc.queryForList("SELECT request_hash,reply FROM contract_operation WHERE operation_key=?", key);
            if (!previous.isEmpty()) {
                if (!previous.getFirst().get("request_hash").equals(hash)) throw new IllegalStateException("幂等冲突");
                return read(previous.getFirst().get("reply").toString());
            }
            String id = body.path("task_id").asText(); String camera; String algorithm;
            if (path.equals("/startTask")) {
                camera = body.path("camera_id").asText(); algorithm = body.path("algorithms").path(0).path("algorithm").asText();
                if (!Set.of("camera-03", "camera-08", "camera-17").contains(camera) || !Set.of("intrusion_detection", "no_helmet", "personnel_violation").contains(algorithm)
                        || !id.equals(HttpIndustrialGateway.taskId(camera, algorithm)) || body.path("rotation_id").asInt(0) != -1) throw new IllegalArgumentException("目标不在白名单");
            } else {
                var matches = jdbc.queryForList("SELECT camera_id,algorithm FROM contract_task WHERE task_id=?", id);
                if (matches.isEmpty()) throw new IllegalArgumentException("任务不存在");
                camera = matches.getFirst().get("camera_id").toString(); algorithm = matches.getFirst().get("algorithm").toString();
            }
            String state = path.equals("/startTask") ? "RUNNING" : "STOPPED";
            Instant now = Instant.now();
            int updated = jdbc.update("UPDATE contract_task SET status=?,updated_at=? WHERE task_id=?", state, Timestamp.from(now), id);
            if (updated == 0) jdbc.update("INSERT INTO contract_task VALUES (?,?,?,?,?)", id, camera, algorithm, state, Timestamp.from(now));
            var result = new DetectionTask(id, camera, algorithm, state, now);
            var reply = Map.of("status", path.equals("/startTask") ? "started" : "stopped", "task_id", id, "camera_id", camera);
            jdbc.update("INSERT INTO contract_operation VALUES (?,?,?,?,?)", key, hash, json(result), json(reply), Timestamp.from(now));
            return reply;
        });
    }
    private List<DetectionTask> tasks(String camera) {
        return jdbc.query("SELECT * FROM contract_task WHERE camera_id=? ORDER BY task_id", (rs, row) -> new DetectionTask(rs.getString("task_id"), rs.getString("camera_id"), rs.getString("algorithm"), rs.getString("status"), rs.getTimestamp("updated_at").toInstant()), camera);
    }
    private Object operation(String key) {
        var values = jdbc.queryForList("SELECT result FROM contract_operation WHERE operation_key=?", String.class, key);
        return values.isEmpty() ? Map.of("status", "NOT_FOUND") : Map.of("status", "COMPLETED", "result", read(values.getFirst()));
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("无法编码响应"); } }
    private JsonNode read(String value) { try { return mapper.readTree(value); } catch (Exception ex) { throw new IllegalArgumentException("回执损坏"); } }
    private void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json(value).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
    }
    public static void main(String[] args) throws Exception {
        String token = System.getenv("MINEGUARD_INDUSTRIAL_TOKEN");
        var source = new DriverManagerDataSource("jdbc:h2:file:./data/runtime/industrial-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var server = new IndustrialContractServer(new JdbcTemplate(source), new ObjectMapper().findAndRegisterModules(), token, args.length == 0 ? 18081 : Integer.parseInt(args[0]));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close)); server.start();
        System.out.println("本地工业契约服务已启动：127.0.0.1:" + server.port() + "；不连接物理设备。");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
