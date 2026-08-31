package com.mineguard.workflow;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** SSE 仅是数据库事件的传输层，各实例使用同一持久化序号补发。 */
@Component
public class TaskEventPublisher {
    private final JdbcAgentTaskStore store;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    public TaskEventPublisher(JdbcAgentTaskStore store) { this.store = store; }

    public List<TaskEvent> history(String id) { return store.history(id, 0, 10000); }
    public List<TaskEvent> history(String id, long after) { return store.history(id, after, 500); }
    public SseEmitter subscribe(String id) { return subscribe(id, 0, () -> true); }
    public SseEmitter subscribe(String id, long after, BooleanSupplier authorized) {
        if (after < 0 || after > store.lastEventSequence(id)) throw new IllegalArgumentException("事件游标超出有效范围");
        var emitter = new SseEmitter(30 * 60 * 1000L);
        var sub = new Subscription(id, after, emitter, authorized);
        subscriptions.add(sub);
        emitter.onCompletion(() -> subscriptions.remove(sub));
        emitter.onTimeout(() -> { subscriptions.remove(sub); emitter.complete(); });
        emitter.onError(error -> subscriptions.remove(sub));
        drain(sub);
        return emitter;
    }
    @Scheduled(fixedDelay = 200) public void poll() { subscriptions.forEach(this::drain); }
    private void drain(Subscription sub) {
        synchronized (sub) {
            try {
                if (!sub.authorized.getAsBoolean()) { close(sub); return; }
                List<TaskEvent> events = store.history(sub.taskId, sub.cursor, 100);
                for (TaskEvent event : events) {
                    sub.emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type().name()).data(event));
                    sub.cursor = event.sequence();
                }
                if (events.size() < 100 && store.findById(sub.taskId).map(t -> t.getState().terminal()).orElse(true)
                        && sub.cursor >= store.lastEventSequence(sub.taskId)) close(sub);
            } catch (Exception ex) { close(sub); }
        }
    }
    private void close(Subscription sub) { subscriptions.remove(sub); try { sub.emitter.complete(); } catch (Exception ignored) { } }
    private static final class Subscription {
        final String taskId; final SseEmitter emitter; final BooleanSupplier authorized; long cursor;
        Subscription(String taskId, long cursor, SseEmitter emitter, BooleanSupplier authorized) {
            this.taskId = taskId; this.cursor = cursor; this.emitter = emitter; this.authorized = authorized;
        }
    }
}
