package com.mineguard.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TaskEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);
    private final Map<String, List<TaskEvent>> history = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public TaskEvent publish(String taskId, TaskEventType type, Map<String, Object> payload) {
        long sequence = sequences.computeIfAbsent(taskId, ignored -> new AtomicLong()).incrementAndGet();
        TaskEvent event = new TaskEvent(sequence, taskId, type, Instant.now(), payload == null ? Map.of() : Map.copyOf(payload));
        history.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        List<SseEmitter> emitters = subscribers.getOrDefault(taskId, List.of());
        emitters.forEach(emitter -> send(emitter, event, taskId));
        if (type == TaskEventType.FINAL_RESULT || type == TaskEventType.ERROR) complete(taskId);
        return event;
    }

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        history(taskId).forEach(event -> send(emitter, event, taskId));
        return emitter;
    }

    public List<TaskEvent> history(String taskId) {
        return List.copyOf(history.getOrDefault(taskId, List.of()));
    }

    private void send(SseEmitter emitter, TaskEvent event, String taskId) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type().name()).data(event));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Removing closed SSE subscriber for {}: {}", taskId, ex.getMessage());
            remove(taskId, emitter);
        }
    }

    private void complete(String taskId) {
        List<SseEmitter> emitters = subscribers.remove(taskId);
        if (emitters != null) emitters.forEach(emitter -> {
            try { emitter.complete(); } catch (Exception ignored) { }
        });
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(taskId);
        if (emitters != null) emitters.remove(emitter);
    }
}
