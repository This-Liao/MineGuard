package com.mineguard.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.security.Actor;
import com.mineguard.security.Digests;
import com.mineguard.tool.ToolExecutionRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

@Repository
public class JdbcAgentTaskStore implements AgentTaskStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public JdbcAgentTaskStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.mapper = mapper; this.tx = new TransactionTemplate(transactions);
    }

    public AgentTask create(String query, Actor actor, String requestKey) {
        validateKey(requestKey);
        String hash = Digests.sha256(query);
        try {
            return tx.execute(status -> {
                AgentTask task = new AgentTask(UUID.randomUUID().toString(), query);
                task.setIdentity(actor.userId(), actor.tenantId());
                jdbc.update("INSERT INTO agent_task(task_id,tenant_id,owner_id,request_key,request_hash,state,snapshot,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        task.getTaskId(), actor.tenantId(), actor.userId(), requestKey, hash, task.getState().name(), json(task.snapshot()), timestamp(task.getCreatedAt()), timestamp(task.getUpdatedAt()));
                appendLocked(task.getTaskId(), List.of(new EventDraft(TaskEventType.TASK_STATE_CHANGED, Map.of("to", "CREATED"))));
                return task;
            });
        } catch (DuplicateKeyException ex) {
            var existing = jdbc.query("SELECT * FROM agent_task WHERE tenant_id=? AND owner_id=? AND request_key=?", this::mapTask,
                    actor.tenantId(), actor.userId(), requestKey);
            if (existing.isEmpty() || !Digests.sha256(existing.getFirst().getUserQuery()).equals(hash)) {
                throw new IllegalStateException("同一幂等键不能用于不同请求");
            }
            return existing.getFirst();
        }
    }

    @Override public Optional<AgentTask> findById(String id) {
        return jdbc.query("SELECT * FROM agent_task WHERE task_id=?", this::mapTask, id).stream().findFirst();
    }
    @Override public List<AgentTask> findAll() { return jdbc.query("SELECT * FROM agent_task ORDER BY created_at DESC LIMIT 200", this::mapTask); }
    public List<AgentTask> visibleTo(Actor actor) {
        boolean all = actor.has(com.mineguard.security.Role.APPROVER) || actor.has(com.mineguard.security.Role.OBSERVER) || actor.has(com.mineguard.security.Role.ADMIN);
        return all ? jdbc.query("SELECT * FROM agent_task WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200", this::mapTask, actor.tenantId())
                : jdbc.query("SELECT * FROM agent_task WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 200", this::mapTask, actor.tenantId(), actor.userId());
    }
    public long lastEventSequence(String id) { return jdbc.queryForObject("SELECT event_sequence FROM agent_task WHERE task_id=?", Long.class, id); }

    public List<String> candidates(int limit) {
        return jdbc.queryForList("SELECT task_id FROM agent_task WHERE state IN ('CREATED','PLANNING','RETRIEVING','ANALYZING','EXECUTING','VERIFYING') AND (lease_owner IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY created_at LIMIT ?", String.class, limit);
    }

    public Optional<Lease> claim(String id, String owner, int seconds) {
        return tx.execute(status -> {
            Instant until = now().plusSeconds(seconds);
            int changed = jdbc.update("UPDATE agent_task SET lease_owner=?,lease_until=?,fence=fence+1 WHERE task_id=? AND state IN ('CREATED','PLANNING','RETRIEVING','ANALYZING','EXECUTING','VERIFYING') AND (lease_owner IS NULL OR lease_until<CURRENT_TIMESTAMP)", owner, timestamp(until), id);
            if (changed == 0) return Optional.empty();
            long fence = jdbc.queryForObject("SELECT fence FROM agent_task WHERE task_id=?", Long.class, id);
            return Optional.of(new Lease(id, owner, fence));
        });
    }

    public boolean renew(Lease lease, int seconds) {
        return jdbc.update("UPDATE agent_task SET lease_until=? WHERE task_id=? AND lease_owner=? AND fence=? AND lease_until>CURRENT_TIMESTAMP",
                timestamp(now().plusSeconds(seconds)), lease.taskId(), lease.owner(), lease.fence()) == 1;
    }
    public void release(Lease lease) {
        jdbc.update("UPDATE agent_task SET lease_owner=NULL,lease_until=NULL WHERE task_id=? AND lease_owner=? AND fence=?", lease.taskId(), lease.owner(), lease.fence());
    }

    public void checkpoint(AgentTask task, Lease lease, List<EventDraft> events) {
        tx.executeWithoutResult(status -> checkpointLocked(task, lease, events));
    }

    private void checkpointLocked(AgentTask task, Lease lease, List<EventDraft> events) {
        int changed = jdbc.update("UPDATE agent_task SET snapshot=?,state=?,version=version+1,updated_at=? WHERE task_id=? AND version=? AND lease_owner=? AND fence=? AND lease_until>CURRENT_TIMESTAMP",
                json(task.snapshot()), task.getState().name(), timestamp(now()), task.getTaskId(), task.getVersion(), lease.owner(), lease.fence());
        if (changed != 1) throw new LeaseLostException();
        appendLocked(task.getTaskId(), events);
        task.setVersion(task.getVersion() + 1);
    }

    public Optional<Step> step(String taskId, String stepId) {
        return jdbc.query("SELECT * FROM task_step WHERE task_id=? AND step_id=?", (rs, row) -> new Step(rs.getString("operation_key"),
                rs.getString("request_hash"), rs.getString("status"), rs.getBoolean("high_risk"),
                rs.getString("result") == null ? null : read(rs.getString("result"), ToolExecutionRecord.class)), taskId, stepId).stream().findFirst();
    }

    public void beginStep(AgentTask task, Lease lease, String stepId, String hash, boolean highRisk, EventDraft event) {
        tx.executeWithoutResult(status -> {
            checkpointLocked(task, lease, List.of(event));
            if (step(task.getTaskId(), stepId).isEmpty()) {
                jdbc.update("INSERT INTO task_step(task_id,step_id,operation_key,request_hash,status,high_risk,updated_at) VALUES (?,?,?,?,?,?,?)",
                        task.getTaskId(), stepId, task.getTaskId() + ":" + stepId, hash, "STARTED", highRisk, timestamp(now()));
            }
        });
    }
    public void completeStep(AgentTask task, Lease lease, String stepId, ToolExecutionRecord result, List<EventDraft> events) {
        tx.executeWithoutResult(status -> {
            checkpointLocked(task, lease, events);
            jdbc.update("UPDATE task_step SET status='COMPLETED',result=?,updated_at=? WHERE task_id=? AND step_id=?",
                    json(result), timestamp(now()), task.getTaskId(), stepId);
        });
    }

    public AgentTask decide(String taskId, Actor actor, String key, String decision, String reason,
                            String planHash, int approvalSeconds, Consumer<AgentTask> authorize) {
        validateKey(key);
        String requestHash = Digests.canonical(mapper, Map.of("decision", decision, "reason", reason, "planHash", planHash));
        return tx.execute(status -> {
            AgentTask task = lockedTask(taskId);
            authorize.accept(task);
            List<String> previous = jdbc.queryForList("SELECT request_hash FROM task_decision WHERE task_id=? AND actor_id=? AND request_key=?", String.class, taskId, actor.userId(), key);
            if (!previous.isEmpty()) {
                if (!previous.getFirst().equals(requestHash)) throw new IllegalStateException("审批幂等键与原请求不一致");
                return task;
            }
            if (task.getState() != AgentTaskState.WAITING_APPROVAL || !Objects.equals(task.getPlanHash(), planHash)) throw new IllegalStateException("任务状态或审批计划版本已变化");
            boolean approved = "APPROVED".equals(decision);
            task.setApproval(approved ? ApprovalDecision.approved(actor.userId(), reason) : ApprovalDecision.rejected(actor.userId(), reason));
            task.bindApproval(planHash, now().plusSeconds(approvalSeconds));
            // 事务内切换状态，杜绝并发批准重复入队。
            task.transitionTo(approved ? AgentTaskState.EXECUTING : AgentTaskState.COMPLETED);
            if (!approved) task.setResult(new com.mineguard.agent.AgentResult(taskId, "操作已被拒绝，未执行系统变更。", task.getPlan().riskLevel(), List.of(), List.of(), task.getEvidence(), List.of(), List.of(), List.of(reason)));
            jdbc.update("UPDATE agent_task SET snapshot=?,state=?,version=version+1,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE task_id=?",
                    json(task.snapshot()), task.getState().name(), timestamp(now()), taskId);
            jdbc.update("INSERT INTO task_decision(task_id,actor_id,request_key,request_hash,decision,created_at) VALUES (?,?,?,?,?,?)",
                    taskId, actor.userId(), key, requestHash, decision, timestamp(now()));
            List<EventDraft> events = new ArrayList<>();
            events.add(new EventDraft(approved ? TaskEventType.APPROVED : TaskEventType.REJECTED, Map.of("actor", actor.userId(), "reason", reason, "planHash", planHash)));
            events.add(new EventDraft(TaskEventType.TASK_STATE_CHANGED, Map.of("from", "WAITING_APPROVAL", "to", task.getState().name())));
            if (!approved) events.add(new EventDraft(TaskEventType.FINAL_RESULT, Map.of("result", task.getResult())));
            appendLocked(taskId, events);
            task.setVersion(task.getVersion() + 1);
            return task;
        });
    }

    public List<TaskEvent> history(String id, long after, int limit) {
        return jdbc.query("SELECT * FROM task_event WHERE task_id=? AND sequence>? ORDER BY sequence LIMIT ?", (rs, row) ->
                new TaskEvent(rs.getLong("sequence"), id, TaskEventType.valueOf(rs.getString("event_type")), rs.getTimestamp("occurred_at").toInstant(),
                        readMap(rs.getString("payload"))), id, after, limit);
    }
    public void append(String id, EventDraft event) {
        tx.executeWithoutResult(status -> { lockedTask(id); appendLocked(id, List.of(event)); });
    }
    private AgentTask lockedTask(String id) {
        return jdbc.query("SELECT * FROM agent_task WHERE task_id=? FOR UPDATE", this::mapTask, id).stream().findFirst().orElseThrow(() -> new NoSuchElementException("任务不存在"));
    }
    private void appendLocked(String id, List<EventDraft> events) {
        long seq = jdbc.queryForObject("SELECT event_sequence FROM agent_task WHERE task_id=?", Long.class, id);
        for (EventDraft event : events) jdbc.update("INSERT INTO task_event(task_id,sequence,event_type,occurred_at,payload) VALUES (?,?,?,?,?)",
                id, ++seq, event.type().name(), timestamp(now()), json(event.payload()));
        jdbc.update("UPDATE agent_task SET event_sequence=? WHERE task_id=?", seq, id);
    }
    public Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, row) -> rs.getTimestamp(1).toInstant()); }
    private AgentTask mapTask(ResultSet rs, int row) throws SQLException {
        AgentTask task = AgentTask.from(read(rs.getString("snapshot"), AgentTask.Snapshot.class));
        task.setVersion(rs.getLong("version")); return task;
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("持久化序列化失败"); } }
    private <T> T read(String value, Class<T> type) { try { return mapper.readValue(value, type); } catch (Exception ex) { throw new IllegalStateException("持久化快照损坏"); } }
    private Map<String, Object> readMap(String value) { try { return mapper.readValue(value, new TypeReference<>() {}); } catch (Exception ex) { throw new IllegalStateException("事件快照损坏"); } }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    public static void validateKey(String key) { if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("必须提供有效 Idempotency-Key"); }
    public record Lease(String taskId, String owner, long fence) {}
    public record EventDraft(TaskEventType type, Map<String, Object> payload) {}
    public record Step(String operationKey, String requestHash, String status, boolean highRisk, ToolExecutionRecord result) {}
    public static final class LeaseLostException extends IllegalStateException { public LeaseLostException() { super("任务租约或版本已失效，禁止旧节点提交"); } }
}
