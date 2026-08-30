package com.mineguard.device;

import java.util.List;

public interface IndustrialGateway {
    DeviceStatus getDeviceStatus(String deviceId);
    List<DetectionTask> listDetectionTasks(String cameraId);
    DetectionTask startDetectionTask(String cameraId, String algorithm);
    DetectionTask stopDetectionTask(String cameraId, String algorithm);
    boolean verifyDetectionTask(String cameraId, String algorithm, String expectedStatus);
}
