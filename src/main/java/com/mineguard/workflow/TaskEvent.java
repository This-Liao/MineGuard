package com.mineguard.workflow;

import java.time.Instant;
import java.util.Map;

public record TaskEvent(long sequence, String taskId, TaskEventType type, Instant timestamp, Map<String, Object> payload) {}
