package com.mineguard.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private final ObjectMapper objectMapper;
    private final Path tracePath;
    private final Map<String, TraceRun> runs = new ConcurrentHashMap<>();

    public TraceRecorder(ObjectMapper objectMapper, MineGuardProperties properties) {
        this.objectMapper = objectMapper;
        this.tracePath = Path.of(properties.tracePath()).toAbsolutePath().normalize();
    }

    public void start(String taskId, String userQuery) {
        runs.put(taskId, new TraceRun(UUID.randomUUID().toString(), taskId, userQuery, Instant.now()));
        record(taskId, "TASK_CREATED", Map.of("query", userQuery));
    }

    public void record(String taskId, String type, Map<String, Object> data) {
        if (taskId == null) return;
        TraceRun run = runs.computeIfAbsent(taskId,
                id -> new TraceRun(UUID.randomUUID().toString(), id, "", Instant.now()));
        run.events.add(new TraceEvent(Instant.now(), type, sanitize(data)));
    }

    public Optional<TraceRunView> get(String taskId) {
        TraceRun run = runs.get(taskId);
        return run == null ? Optional.empty() : Optional.of(run.view());
    }

    public void complete(String taskId, Object result) {
        TraceRun run = runs.get(taskId);
        if (run == null) return;
        run.finishedAt = Instant.now();
        run.result = result;
        persist(run);
    }

    private void persist(TraceRun run) {
        try {
            Files.createDirectories(tracePath);
            Path target = tracePath.resolve(run.taskId + ".json");
            Path temp = Files.createTempFile(tracePath, run.taskId, ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), run.view());
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException noAtomicMove) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.warn("Unable to persist trace for task {}: {}", run.taskId, ex.getMessage());
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (!normalized.contains("key") && !normalized.contains("secret") && !normalized.contains("password")
                    && !normalized.contains("chainofthought") && !normalized.contains("cot")) {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private static final class TraceRun {
        private final String runId;
        private final String taskId;
        private final String userQuery;
        private final Instant startedAt;
        private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
        private volatile Instant finishedAt;
        private volatile Object result;

        private TraceRun(String runId, String taskId, String userQuery, Instant startedAt) {
            this.runId = runId;
            this.taskId = taskId;
            this.userQuery = userQuery;
            this.startedAt = startedAt;
        }

        private TraceRunView view() {
            Instant end = finishedAt == null ? Instant.now() : finishedAt;
            return new TraceRunView(runId, taskId, userQuery, startedAt, finishedAt,
                    Duration.between(startedAt, end).toMillis(),
                    byType("STATE_TRANSITION"), byType("TOOL_CALL"), byType("RETRIEVAL"),
                    byType("APPROVAL"), byType("ERROR"), List.copyOf(events), result);
        }

        private List<TraceEvent> byType(String type) {
            return events.stream().filter(event -> event.type().equals(type)).toList();
        }
    }

    public record TraceRunView(String runId, String taskId, String userQuery, Instant startedAt,
                               Instant finishedAt, long durationMs, List<TraceEvent> stateTransitions,
                               List<TraceEvent> toolCalls, List<TraceEvent> retrieval,
                               List<TraceEvent> approval, List<TraceEvent> errors,
                               List<TraceEvent> events, Object result) {}
}
