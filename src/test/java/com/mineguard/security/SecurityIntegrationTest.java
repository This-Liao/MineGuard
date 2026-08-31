package com.mineguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired ObjectMapper mapper;
    @Autowired AgentWorkflowEngine workflow;
    @Autowired JdbcTemplate jdbc;
    private final String password = "Secure-test-password-2026";
    private Actor operator, reviewer, observer, admin, outsider;
    private String operatorToken, reviewerToken, observerToken, adminToken, outsiderToken;

    @BeforeAll void accounts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        operator = identities.createAccount("security-test", "op-" + suffix, password, Set.of(Role.OPERATOR));
        reviewer = identities.createAccount("security-test", "review-" + suffix, password, Set.of(Role.OPERATOR, Role.APPROVER));
        observer = identities.createAccount("security-test", "view-" + suffix, password, Set.of(Role.OBSERVER));
        admin = identities.createAccount("security-test", "admin-" + suffix, password, Set.of(Role.ADMIN));
        outsider = identities.createAccount("other-tenant", "outside-" + suffix, password, Set.of(Role.ADMIN, Role.OBSERVER));
        operatorToken = token(operator); reviewerToken = token(reviewer); observerToken = token(observer); adminToken = token(admin); outsiderToken = token(outsider);
    }
    private String token(Actor actor) { return identities.login(actor.username(), password).accessToken(); }

    @Test void realModelArchiveIsAuthenticatedAndSeparateFromDeterministicSnapshot() throws Exception {
        mvc.perform(get("/api/eval/real")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/eval/comparison")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/eval/comparison").header("Authorization", "Bearer " + observerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseline.agent.taskSuccessRate").value(0.3))
                .andExpect(jsonPath("$.candidate.status").exists());
        mvc.perform(get("/api/eval/real").header("Authorization", "Bearer " + observerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usage.attempts").value(55))
                .andExpect(jsonPath("$.agent.taskSuccessRate").value(0.3));
        mvc.perform(get("/api/eval/latest").header("Authorization", "Bearer " + observerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evaluationMode").value("Deterministic Evaluation"));
    }

    @Test void anonymousAndTokenInQueryAreRejected() throws Exception {
        mvc.perform(get("/api/agent/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/tasks/unknown/report")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/tasks").param("access_token", operatorToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
    @Test void loginReturnsBearerAndDatabaseStoresOnlyHash() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username", operator.username(), "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.user.userId").value(operator.userId()));
        assertThat(jdbc.queryForList("SELECT token_hash FROM auth_session WHERE user_id=?", String.class, operator.userId())).doesNotContain(operatorToken).allMatch(t -> t.length() == 64);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE user_id=?", String.class, operator.userId());
        assertThat(hash).startsWith("$2").doesNotContain(password);
    }
    @Test void logoutAndExpiryInvalidateSessions() throws Exception {
        String temporary = token(operator);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + temporary)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + temporary)).andExpect(status().isUnauthorized());
        temporary = token(operator);
        jdbc.update("UPDATE auth_session SET expires_at=? WHERE token_hash=?", Timestamp.from(Instant.now().minusSeconds(1)), Digests.sha256(temporary));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + temporary)).andExpect(status().isUnauthorized());
    }
    @Test void observersCannotCreateAndOperatorsCannotManageAccounts() throws Exception {
        mvc.perform(post("/api/agent/tasks").header("Authorization", "Bearer " + observerToken).header("Idempotency-Key", "readonly").contentType("application/json").content("{\"query\":\"查询安全帽规范\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + operatorToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    }
    @Test void realPrincipalOverridesForgedActorAndSelfApprovalIsForbidden() throws Exception {
        AgentTask task = workflow.create("启动 camera-03 的 intrusion_detection", reviewer, UUID.randomUUID().toString());
        task = awaitWaiting(task);
        mvc.perform(post("/api/tasks/{id}/approve", task.getTaskId()).header("Authorization", "Bearer " + reviewerToken).header("Idempotency-Key", "self-approval")
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("actor", "pretend-other", "reason", "确认", "planHash", task.getPlanHash()))))
                .andExpect(status().isForbidden());
    }
    @Test void adminRoleCannotBypassApprovalRole() throws Exception {
        AgentTask task = awaitWaiting(workflow.create("启动 camera-03 的 intrusion_detection", operator, UUID.randomUUID().toString()));
        mvc.perform(post("/api/tasks/{id}/approve", task.getTaskId()).header("Authorization", "Bearer " + adminToken).header("Idempotency-Key", "admin-bypass")
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("reason", "确认", "planHash", task.getPlanHash()))))
                .andExpect(status().isForbidden());
    }
    @Test void crossTenantCannotReadEventsOrSubscribe() throws Exception {
        AgentTask task = workflow.create("查询规范", operator, UUID.randomUUID().toString());
        for (String suffix : List.of("", "/events", "/stream", "/report")) {
            mvc.perform(get("/api/agent/tasks/" + task.getTaskId() + suffix).header("Authorization", "Bearer " + outsiderToken)).andExpect(status().isNotFound());
        }
    }
    @Test void disabledUserSessionsAreRevoked() throws Exception {
        Actor disposable = identities.createAccount("security-test", "disable-" + UUID.randomUUID().toString().substring(0, 8), password, Set.of(Role.OPERATOR));
        String temporary = token(disposable);
        mvc.perform(post("/api/admin/users/{id}/enabled", disposable.userId()).header("Authorization", "Bearer " + adminToken).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + temporary)).andExpect(status().isUnauthorized());
    }
    @Test void repeatedLoginFailuresArePersistentlyThrottled() {
        Actor user = identities.createAccount("security-test", "throttle-" + UUID.randomUUID().toString().substring(0, 8), password, Set.of(Role.OBSERVER));
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> identities.login(user.username(), "wrong-password")).hasMessageContaining("凭据无效");
        assertThatThrownBy(() -> identities.login(user.username(), password)).hasMessageContaining("凭据无效");
        jdbc.update("UPDATE auth_throttle SET locked_until=? WHERE subject_hash=?", Timestamp.from(Instant.now().minusSeconds(1)), Digests.sha256(user.username()));
        assertThat(token(user)).hasSize(43);
    }
    @Test void requestValidationDoesNotEchoPassword() throws Exception {
        String secret = "x".repeat(100);
        String body = mvc.perform(post("/api/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username", "test", "password", secret))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(secret);
    }
    private AgentTask awaitWaiting(AgentTask task) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (task.getState() != AgentTaskState.WAITING_APPROVAL && System.nanoTime() < deadline) { Thread.sleep(20); task = workflow.get(task.getTaskId()); }
        assertThat(task.getState()).isEqualTo(AgentTaskState.WAITING_APPROVAL); return task;
    }
}
