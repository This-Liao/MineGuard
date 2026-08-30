package com.mineguard.device;

import java.time.Instant;

public record DetectionTask(String taskId, String cameraId, String algorithm, String status, Instant updatedAt) {}
