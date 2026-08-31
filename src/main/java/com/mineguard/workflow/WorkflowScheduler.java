package com.mineguard.workflow;

import com.mineguard.config.MineGuardProperties;
import com.mineguard.config.RuntimeProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import static com.mineguard.workflow.JdbcAgentTaskStore.Lease;

/** 仅负责节点容量、租约领取和心跳；状态机与步骤执行不依赖调度器。 */
@Component
public class WorkflowScheduler {
    private final JdbcAgentTaskStore store;
    private final AgentWorkflowEngine engine;
    private final ExecutorService executor;
    private final RuntimeProperties runtime;
    private final int capacity;
    private final String worker;
    private final Map<String, Lease> active = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public WorkflowScheduler(JdbcAgentTaskStore store, AgentWorkflowEngine engine, ExecutorService workflowExecutor,
                             RuntimeProperties runtime, MineGuardProperties config) {
        this.store = store; this.engine = engine; this.executor = workflowExecutor; this.runtime = runtime;
        this.capacity = Math.max(1, config.workflowExecutorThreads());
        this.worker = runtime.nodeId() + "-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${mineguard.runtime.poll-ms:200}")
    public synchronized void dispatch() {
        if (stopped || !runtime.schedulerEnabled()) return;
        for (String id : store.candidates(capacity * 2)) {
            if (active.size() >= capacity) break;
            if (active.containsKey(id)) continue;
            store.claim(id, worker, runtime.leaseSeconds()).ifPresent(lease -> {
                active.put(id, lease);
                try {
                    executor.submit(() -> {
                        try { engine.runLease(lease); }
                        finally { active.remove(id, lease); }
                    });
                } catch (RuntimeException ex) { active.remove(id, lease); store.release(lease); }
            });
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void heartbeat() {
        if (!stopped) active.values().forEach(lease -> {
            try { store.renew(lease, runtime.leaseSeconds()); }
            catch (RuntimeException ignored) { /* 无法续约时，后续提交由数据库的 fencing 检查拒绝。 */ }
        });
    }

    @PreDestroy public void stop() { stopped = true; executor.shutdownNow(); }
}
