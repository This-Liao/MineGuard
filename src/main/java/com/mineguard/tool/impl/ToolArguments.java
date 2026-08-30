package com.mineguard.tool.impl;

import com.mineguard.event.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

final class ToolArguments {
    private ToolArguments() {}

    static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    static int integer(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    static SafetyEventFilter eventFilter(Map<String, Object> args) {
        return new SafetyEventFilter(
                string(args, "area"),
                enumValue(EventType.class, string(args, "eventType")),
                instant(string(args, "startTime")),
                instant(string(args, "endTime")),
                enumValue(Severity.class, string(args, "severity")));
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("time must be ISO-8601 instant: " + value);
        }
    }

    static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unsupported " + type.getSimpleName() + ": " + value);
        }
    }
}
