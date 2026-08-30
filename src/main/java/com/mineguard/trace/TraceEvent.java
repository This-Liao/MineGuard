package com.mineguard.trace;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(Instant timestamp, String type, Map<String, Object> data) {}
