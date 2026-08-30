package com.mineguard.device;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockIndustrialGateway implements IndustrialGateway {
    private final Map<String, DeviceStatus> devices = new ConcurrentHashMap<>();
    private final Map<String, DetectionTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    public MockIndustrialGateway() {
        for (int i = 1; i <= 24; i++) devices.put("camera-%02d".formatted(i), DeviceStatus.ONLINE);
        devices.put("camera-17", DeviceStatus.DEGRADED);
        devices.put("camera-21", DeviceStatus.OFFLINE);
        putTask("camera-03", "intrusion_detection", "STOPPED");
        putTask("camera-17", "personnel_violation", "STOPPED");
        putTask("camera-08", "no_helmet", "RUNNING");
    }

    @Override
    public DeviceStatus getDeviceStatus(String deviceId) {
        return devices.getOrDefault(deviceId, DeviceStatus.OFFLINE);
    }

    @Override
    public List<DetectionTask> listDetectionTasks(String cameraId) {
        return tasks.values().stream()
                .filter(task -> cameraId == null || cameraId.isBlank() || task.cameraId().equals(cameraId))
                .sorted(java.util.Comparator.comparing(DetectionTask::taskId))
                .toList();
    }

    @Override
    public DetectionTask startDetectionTask(String cameraId, String algorithm) {
        requireOperational(cameraId);
        return putTask(cameraId, algorithm, "RUNNING");
    }

    @Override
    public DetectionTask stopDetectionTask(String cameraId, String algorithm) {
        requireKnown(cameraId);
        return putTask(cameraId, algorithm, "STOPPED");
    }

    @Override
    public boolean verifyDetectionTask(String cameraId, String algorithm, String expectedStatus) {
        DetectionTask task = tasks.get(key(cameraId, algorithm));
        return task != null && task.status().equalsIgnoreCase(expectedStatus);
    }

    private DetectionTask putTask(String cameraId, String algorithm, String status) {
        DetectionTask task = new DetectionTask("DET-" + ids.incrementAndGet(), cameraId, algorithm, status, Instant.now());
        tasks.put(key(cameraId, algorithm), task);
        return task;
    }

    private void requireOperational(String cameraId) {
        requireKnown(cameraId);
        if (devices.get(cameraId) == DeviceStatus.OFFLINE) throw new IllegalStateException("device is offline: " + cameraId);
    }

    private void requireKnown(String cameraId) {
        if (!devices.containsKey(cameraId)) throw new IllegalArgumentException("unknown device: " + cameraId);
    }

    private String key(String cameraId, String algorithm) {
        return cameraId + "::" + algorithm;
    }
}
