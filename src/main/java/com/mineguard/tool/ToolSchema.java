package com.mineguard.tool;

import java.util.*;

public record ToolSchema(Map<String, Field> properties, Set<String> required, boolean additionalProperties) {
    public ToolSchema {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        required = required == null ? Set.of() : Set.copyOf(required);
    }

    public List<String> validate(Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        List<String> errors = new ArrayList<>();
        for (String key : required) {
            Object value = safeArgs.get(key);
            if (value == null || (value instanceof String s && s.isBlank())) errors.add("missing required argument: " + key);
        }
        if (!additionalProperties) {
            safeArgs.keySet().stream().filter(key -> !properties.containsKey(key))
                    .forEach(key -> errors.add("unknown argument: " + key));
        }
        safeArgs.forEach((key, value) -> {
            Field field = properties.get(key);
            if (field != null && value != null && !field.accepts(value)) errors.add("invalid type/value for argument: " + key);
        });
        return errors;
    }

    public Map<String, Object> asJsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        properties.forEach((name, field) -> {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("type", field.type());
            if (!field.allowedValues().isEmpty()) definition.put("enum", field.allowedValues());
            props.put(name, definition);
        });
        return Map.of("type", "object", "properties", props, "required", required,
                "additionalProperties", additionalProperties);
    }

    public record Field(String type, Set<String> allowedValues) {
        public Field {
            allowedValues = allowedValues == null ? Set.of() : Set.copyOf(allowedValues);
        }
        public static Field string() { return new Field("string", Set.of()); }
        public static Field stringEnum(String... values) { return new Field("string", Set.of(values)); }
        public static Field integer() { return new Field("integer", Set.of()); }
        boolean accepts(Object value) {
            boolean correctType = switch (type) {
                case "string" -> value instanceof String;
                case "integer" -> value instanceof Integer || value instanceof Long;
                case "boolean" -> value instanceof Boolean;
                default -> true;
            };
            return correctType && (allowedValues.isEmpty() || allowedValues.contains(String.valueOf(value)));
        }
    }
}
