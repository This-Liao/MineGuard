package com.mineguard.event;

import java.time.Instant;

public record SafetyEvent(
        String eventId,
        String area,
        String deviceId,
        EventType eventType,
        Severity severity,
        Instant timestamp,
        String description,
        String status
) {}
