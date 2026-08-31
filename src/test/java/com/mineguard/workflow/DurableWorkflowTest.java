package com.mineguard.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.device.IndustrialGateway;
import com.mineguard.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"mineguard.runtime.scheduler-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:durable_tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
class DurableWorkflowTest {
    @Autowired JdbcAgentTaskStore store;
    @Autowired AgentWorkflowEngine engine;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    @Autowired IndustrialGateway gateway;
    @Autowired TaskAccessPolicy policy;

    private AgentTask create(String query) { return engine.create(query); }
    private AgentTask run(AgentTask task, String node) { engine.runLease(store.claim(task.getTaskId(), node, 30).orElseThrow()); return engine.get(task.getTaskId()); }
    private void expire(String id) { jdbc.update("UPDATE agent_task SET lease_until=? WHERE task_id=?", Timestamp.from(store.now().minusSeconds(1)), id); }
    private AgentTask waiting() { return run(create("启动 camera-03 的 intrusion_detection 检测任务"), "initial"); }

    @Test void atomicCreationDeduplicatesConcurrentRequests() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 16).mapToObj(i -> pool.submit(() -> engine.create("查询安全帽规范", Actor.internal("creator"), key).getTaskId())).toList();
            Set<String> ids = new HashSet<>(); for (var job : jobs) ids.add(job.get());
            assertThat(ids).hasSize(1);
            assertThat(store.history(ids.iterator().next(), 0, 100)).hasSize(1);
        }
        assertThatThrownBy(() -> engine.create("不同请求", Actor.internal("creator"), key)).hasMessageContaining("幂等键");
    }

    @Test void twoRepositoriesCompeteAndOldFenceCannotCommit() {
        var other = new JdbcAgentTaskStore(jdbc, mapper, tm);
        AgentTask task = create("查询安全帽规范");
        var old = store.claim(task.getTaskId(), "node-a", 30).orElseThrow();
        assertThat(other.claim(task.getTaskId(), "node-b", 30)).isEmpty();
        expire(task.getTaskId());
        var replacement = other.claim(task.getTaskId(), "node-b", 30).orElseThrow();
        assertThat(replacement.fence()).isGreaterThan(old.fence());
        assertThat(store.renew(old, 30)).isFalse();
        assertThatThrownBy(() -> store.checkpoint(task, old, List.of())).isInstanceOf(JdbcAgentTaskStore.LeaseLostException.class);
        store.release(old);
        assertThat(other.renew(replacement, 30)).isTrue();
        engine.runLease(replacement);
        assertThat(other.findById(task.getTaskId()).orElseThrow().getState()).isEqualTo(AgentTaskState.COMPLETED);
    }

    @Test void resumePlanningAfterLeaseExpiryAndRebuildTraceFromDatabase() {
        AgentTask task = create("查询安全帽规范");
        var lease = store.claim(task.getTaskId(), "crashed-node", 30).orElseThrow();
        task.transitionTo(AgentTaskState.PLANNING);
        store.checkpoint(task, lease, List.of(new JdbcAgentTaskStore.EventDraft(TaskEventType.TASK_STATE_CHANGED, Map.of("to", "PLANNING"))));
        expire(task.getTaskId());
        AgentTask recovered = run(task, "replacement");
        assertThat(recovered.getState()).isEqualTo(AgentTaskState.COMPLETED);
        assertThat(recovered.getEvidence()).isNotEmpty();
        var history = new TaskEventPublisher(new JdbcAgentTaskStore(jdbc, mapper, tm)).history(task.getTaskId());
        assertThat(history).extracting(TaskEvent::sequence).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, history.size()).boxed().toList());
        assertThat(store.history(task.getTaskId(), 2, 500)).allMatch(e -> e.sequence() > 2);
    }

    @Test void waitingApprovalIsDurableAndNeverAutomaticallyClaimed() {
        AgentTask task = waiting();
        assertThat(task.getState()).isEqualTo(AgentTaskState.WAITING_APPROVAL);
        assertThat(store.claim(task.getTaskId(), "other-node", 30)).isEmpty();
        AgentTask loaded = new JdbcAgentTaskStore(jdbc, mapper, tm).findById(task.getTaskId()).orElseThrow();
        assertThat(loaded.getPlanHash()).isEqualTo(task.getPlanHash());
        assertThat(loaded.getToolCalls()).hasSameSizeAs(task.getToolCalls());
    }

    @Test void concurrentApprovalUsesOneDecisionAndCannotReuseKeyWithDifferentBody() throws Exception {
        AgentTask task = waiting(); String key = UUID.randomUUID().toString(); Actor reviewer = Actor.internal("reviewer");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> engine.decide(task.getTaskId(), reviewer, true, "确认", task.getPlanHash(), key));
            var two = pool.submit(() -> engine.decide(task.getTaskId(), reviewer, true, "确认", task.getPlanHash(), key));
            assertThat(one.get().getState()).isEqualTo(AgentTaskState.EXECUTING);
            assertThat(two.get().getState()).isEqualTo(AgentTaskState.EXECUTING);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_decision WHERE task_id=?", Long.class, task.getTaskId())).isEqualTo(1);
        assertThatThrownBy(() -> engine.decide(task.getTaskId(), reviewer, false, "确认", task.getPlanHash(), key)).hasMessageContaining("幂等键");
        assertThat(run(task, "approved-worker").getState()).isEqualTo(AgentTaskState.COMPLETED);
    }

    @Test void selfApprovalAndStalePlanAreRejected() {
        AgentTask task = waiting();
        assertThatThrownBy(() -> engine.decide(task.getTaskId(), Actor.internal("evaluation-runner"), true, "自批", task.getPlanHash(), "self"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> engine.decide(task.getTaskId(), Actor.internal("other"), true, "确认", "outdated", "stale"))
                .hasMessageContaining("版本");
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(AgentTaskState.WAITING_APPROVAL);
    }

    @Test void unknownHighRiskOutcomeIsNotReplayed() {
        gateway.stopDetectionTask("camera-03", "intrusion_detection");
        AgentTask task = waiting();
        task = engine.approve(task.getTaskId(), "other", "恢复场景");
        var lease = store.claim(task.getTaskId(), "crashed-before-ack", 30).orElseThrow();
        PlanStep step = task.getPlan().steps().stream().filter(s -> s.type() == AgentStepType.START_DETECTION_TASK).findFirst().orElseThrow();
        store.beginStep(task, lease, step.id(), Digests.canonical(mapper, step), true,
                new JdbcAgentTaskStore.EventDraft(TaskEventType.TOOL_STARTED, Map.of("stepId", step.id())));
        expire(task.getTaskId());
        AgentTask recovered = run(task, "recovery-node");
        assertThat(recovered.getState()).isEqualTo(AgentTaskState.RECOVERY_REQUIRED);
        assertThat(recovered.getToolCalls()).noneMatch(c -> c.toolName().equals("start_detection_task"));
        assertThat(gateway.verifyDetectionTask("camera-03", "intrusion_detection", "STOPPED")).isTrue();
        assertThat(store.claim(task.getTaskId(), "again", 30)).isEmpty();
    }

    @Test void expiredApprovalNeverExecutesHighRiskStep() {
        AgentTask task = waiting(); task = engine.approve(task.getTaskId(), "other", "确认");
        var lease = store.claim(task.getTaskId(), "expired-approval", 30).orElseThrow();
        task.bindApproval(task.getPlanHash(), store.now().minusSeconds(1));
        store.checkpoint(task, lease, List.of());
        engine.runLease(lease);
        AgentTask loaded = engine.get(task.getTaskId());
        assertThat(loaded.getState()).isEqualTo(AgentTaskState.FAILED);
        assertThat(loaded.getError()).contains("过期");
        assertThat(loaded.getToolCalls()).noneMatch(c -> c.toolName().equals("start_detection_task"));
    }

    @Test void postApprovalPlanTamperingIsRejected() {
        AgentTask task = waiting(); task = engine.approve(task.getTaskId(), "other", "确认");
        var lease = store.claim(task.getTaskId(), "tamper", 30).orElseThrow();
        task.setPlan(new AgentPlan("被修改", task.getPlan().riskLevel(), task.getPlan().steps()));
        store.checkpoint(task, lease, List.of()); engine.runLease(lease);
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(AgentTaskState.FAILED);
    }

    @Test void crossTenantAndOtherOperatorCannotReadTask() {
        Actor owner = new Actor("owner", "tenant-a", "owner", Set.of(Role.OPERATOR));
        AgentTask task = engine.create("查询规范", owner, "read-isolation");
        Actor peer = new Actor("peer", "tenant-a", "peer", Set.of(Role.OPERATOR));
        Actor outsider = new Actor("outsider", "tenant-b", "outsider", Set.of(Role.ADMIN, Role.OBSERVER));
        assertThatThrownBy(() -> engine.get(task.getTaskId(), peer)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> engine.get(task.getTaskId(), outsider)).isInstanceOf(NoSuchElementException.class);
        assertThat(engine.list(peer)).isEmpty(); assertThat(engine.list(outsider)).isEmpty();
        assertThat(policy.canRead(new Actor("observer", "tenant-a", "observer", Set.of(Role.OBSERVER)), task)).isTrue();
    }

    @Test void sseRejectsFutureCursorAndReplaysCompletedHistory() {
        AgentTask task = run(create("查询安全帽规范"), "sse-node");
        var publisher = new TaskEventPublisher(store);
        assertThatThrownBy(() -> publisher.subscribe(task.getTaskId(), 9999, () -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> publisher.subscribe(task.getTaskId(), 1, () -> true)).doesNotThrowAnyException();
        assertThat(publisher.history(task.getTaskId(), 1)).allMatch(e -> e.sequence() > 1);
        assertThatCode(() -> publisher.subscribe(task.getTaskId(), 0, () -> false)).doesNotThrowAnyException();
    }
}
