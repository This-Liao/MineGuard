package com.mineguard.event;

import java.time.Instant;

public record SafetyEventFilter(
        String area,
        EventType eventType,
        Instant startTime,
        Instant endTime,
        Severity severity
) {}
