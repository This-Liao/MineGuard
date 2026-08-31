package com.mineguard.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.security.Digests;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/** 对接本地工业契约服务。contract/* 是明确的测试扩展，不冒充原 PDF 已有接口。 */
@Component
@ConditionalOnProperty(name = "mineguard.industrial.type", havingValue = "http-contract")
public class HttpIndustrialGateway implements IndustrialGateway {
    private final URI base;
    private final String token;
    private final Set<String> cameras;
    private final Set<String> algorithms;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HttpIndustrialGateway(@Value("${mineguard.industrial.base-url:http://127.0.0.1:18081}") String base,
                                 @Value("${mineguard.industrial.token:}") String token,
                                 @Value("${mineguard.industrial.write-cameras:camera-03,camera-08,camera-17}") String cameras,
                                 @Value("${mineguard.industrial.write-algorithms:intrusion_detection,no_helmet,personnel_violation}") String algorithms,
                                 ObjectMapper mapper) {
        URI uri = URI.create(base.replaceAll("/+$", ""));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost").contains(uri.getHost())))) throw new IllegalArgumentException("工业地址必须为 HTTPS 或本机回环 HTTP");
        if (token == null || token.length() < 16) throw new IllegalArgumentException("工业契约服务需要至少 16 字符的访问凭据");
        this.base = uri; this.token = token; this.cameras = Set.of(cameras.split(",")); this.algorithms = Set.of(algorithms.split(",")); this.mapper = mapper;
    }
    @Override public DeviceStatus getDeviceStatus(String camera) {
        JsonNode result = post("/contract/device-status", Map.of("camera_id", camera), null, false);
        return DeviceStatus.valueOf(result.path("status").asText("UNKNOWN"));
    }
    @Override public List<DetectionTask> listDetectionTasks(String camera) {
        JsonNode result = post("/contract/tasks", Map.of("camera_id", camera == null ? "" : camera), null, false);
        if (!result.isArray()) throw new IllegalStateException("工业任务查询响应无效");
        List<DetectionTask> tasks = new ArrayList<>(); result.forEach(item -> tasks.add(mapper.convertValue(item, DetectionTask.class))); return tasks;
    }
    @Override public DetectionTask startDetectionTask(String camera, String algorithm) { return startDetectionTask(camera, algorithm, UUID.randomUUID().toString()); }
    @Override public DetectionTask stopDetectionTask(String camera, String algorithm) { return stopDetectionTask(camera, algorithm, UUID.randomUUID().toString()); }
    @Override public DetectionTask startDetectionTask(String camera, String algorithm, String key) {
        allowWrite(camera, algorithm);
        JsonNode response = post("/startTask", Map.of("task_id", taskId(camera, algorithm), "camera_id", camera, "rotation_id", -1,
                "algorithms", List.of(Map.of("algorithm", algorithm))), key, true);
        if (!"started".equals(response.path("status").asText())) throw new IndustrialOutcomeUnknownException();
        return receiptRequired(key, camera, algorithm, "RUNNING");
    }
    @Override public DetectionTask stopDetectionTask(String camera, String algorithm, String key) {
        allowWrite(camera, algorithm);
        JsonNode response = post("/stopTask", Map.of("task_id", taskId(camera, algorithm)), key, true);
        if (!"stopped".equals(response.path("status").asText())) throw new IndustrialOutcomeUnknownException();
        return receiptRequired(key, camera, algorithm, "STOPPED");
    }
    @Override public boolean verifyDetectionTask(String camera, String algorithm, String expected) {
        JsonNode result = post("/check", Map.of("task_id", taskId(camera, algorithm)), null, false);
        String status = result.path("status").asText();
        return ("RUNNING".equals(expected) && "任务已运行".equals(status)) || ("STOPPED".equals(expected) && "任务已停止".equals(status));
    }
    @Override public Optional<DetectionTask> operationReceipt(String key) {
        JsonNode response = post("/contract/operation", Map.of("operation_key", key), null, false);
        return "COMPLETED".equals(response.path("status").asText()) && response.path("result").isObject()
                ? Optional.of(mapper.convertValue(response.path("result"), DetectionTask.class)) : Optional.empty();
    }
    private DetectionTask receiptRequired(String key, String camera, String algorithm, String status) {
        try {
            return operationReceipt(key).filter(r -> r.cameraId().equals(camera) && r.algorithm().equals(algorithm) && r.status().equals(status)).orElseThrow(IndustrialOutcomeUnknownException::new);
        } catch (Exception ex) { throw new IndustrialOutcomeUnknownException(); }
    }
    private void allowWrite(String camera, String algorithm) {
        if (!cameras.contains(camera) || !algorithms.contains(algorithm)) throw new IllegalArgumentException("设备或算法不在写操作白名单");
    }
    public static String taskId(String camera, String algorithm) { return "det_" + Digests.sha256(camera + "::" + algorithm).substring(0, 32); }
    private JsonNode post(String path, Object data, String operationKey, boolean write) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-Adapter-Key", token);
            if (operationKey != null) builder.header("Idempotency-Key", operationKey);
            var request = builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(data))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 409) throw new IllegalArgumentException("工业服务拒绝请求，HTTP " + response.statusCode());
            if (response.statusCode() != 200) { if (write) throw new IndustrialOutcomeUnknownException(); throw new IllegalStateException("工业查询失败，HTTP " + response.statusCode()); }
            return mapper.readTree(response.body());
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            if (write) throw new IndustrialOutcomeUnknownException();
            throw new IllegalStateException("工业查询不可用，无法确认状态");
        }
    }
}
