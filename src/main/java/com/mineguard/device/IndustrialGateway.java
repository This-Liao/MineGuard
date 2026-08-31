package com.mineguard.device;

import java.util.List;
import java.util.Optional;

public interface IndustrialGateway {
    DeviceStatus getDeviceStatus(String deviceId);
    List<DetectionTask> listDetectionTasks(String cameraId);
    DetectionTask startDetectionTask(String cameraId, String algorithm);
    DetectionTask stopDetectionTask(String cameraId, String algorithm);
    default DetectionTask startDetectionTask(String cameraId, String algorithm, String operationKey) { return startDetectionTask(cameraId, algorithm); }
    default DetectionTask stopDetectionTask(String cameraId, String algorithm, String operationKey) { return stopDetectionTask(cameraId, algorithm); }
    boolean verifyDetectionTask(String cameraId, String algorithm, String expectedStatus);
    default Optional<DetectionTask> operationReceipt(String operationKey) { return Optional.empty(); }
}
