package com.mineguard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.workflow.AgentTaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.mineguard.security.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityService identities;
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private String reviewToken;

    @BeforeEach void authenticate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        identities.createAccount("api-test", "operator-" + suffix, "Test-password-2026", Set.of(Role.OPERATOR));
        identities.createAccount("api-test", "reviewer-" + suffix, "Test-password-2026", Set.of(Role.APPROVER));
        String token = identities.login("operator-" + suffix, "Test-password-2026").accessToken();
        reviewToken = identities.login("reviewer-" + suffix, "Test-password-2026").accessToken();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).defaultRequest(get("/")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())).build();
    }

    @Test
    void createsAndReadsTaskThroughApi() throws Exception {
        String body = mvc.perform(post("/api/agent/tasks").contentType("application/json")
                        .content("{\"query\":\"查询安全事件处置规范\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).path("taskId").asText();
        mvc.perform(get("/api/agent/tasks/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(id));
        mvc.perform(get("/api/agent/tasks/{id}/events", id)).andExpect(status().isOk());
        awaitState(id, AgentTaskState.COMPLETED);
        mvc.perform(get("/api/agent/tasks/{id}/report", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("grounded-report-v1"))
                .andExpect(jsonPath("$.citations").isNotEmpty());
    }

    @Test
    void exposesApprovalAndSseEndpoints() throws Exception {
        String body = mvc.perform(post("/api/agent/tasks").contentType("application/json")
                        .content("{\"query\":\"启动 camera-08 的 no_helmet 检测\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).path("taskId").asText();
        awaitState(id, AgentTaskState.WAITING_APPROVAL);
        mvc.perform(get("/api/agent/tasks/{id}/report", id)).andExpect(status().isConflict());

        mvc.perform(get("/api/agent/tasks/{id}/stream", id))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        String taskJson = mvc.perform(get("/api/agent/tasks/{id}", id)).andReturn().getResponse().getContentAsString();
        String planHash = mapper.readTree(taskJson).path("planHash").asText();
        mvc.perform(post("/api/tasks/{id}/reject", id).header("Authorization", "Bearer " + reviewToken).contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("planHash", planHash, "reason", "拒绝测试"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approval.status").value("REJECTED"));
        awaitState(id, AgentTaskState.COMPLETED);
    }

    @Test
    void validatesBlankRequestAndMissingTask() throws Exception {
        mvc.perform(post("/api/agent/tasks").contentType("application/json").content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent/tasks/missing")).andExpect(status().isNotFound());
    }

    @Test
    void legacyReportIsReadOnlyAndUsesPersistedReceipts() throws Exception {
        String body = mvc.perform(post("/api/agent/tasks").contentType("application/json")
                .content("{\"query\":\"查询安全帽规范\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).path("taskId").asText();
        awaitState(id, AgentTaskState.COMPLETED);
        // 只把本测试 UUID 任务改为旧格式，验证历史快照兼容，不接触用户数据。
        var snapshot = mapper.readTree(jdbc.queryForObject("SELECT snapshot FROM agent_task WHERE task_id=?", String.class, id));
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("result")).remove("report");
        String legacy = mapper.writeValueAsString(snapshot);
        jdbc.update("UPDATE agent_task SET snapshot=? WHERE task_id=?", legacy, id);
        Long eventCount = jdbc.queryForObject("SELECT event_sequence FROM agent_task WHERE task_id=?", Long.class, id);
        mvc.perform(get("/api/agent/tasks/{id}/report", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("grounded-report-v1"))
                .andExpect(jsonPath("$.citations[0].kind").value("KNOWLEDGE"));
        assertThat(jdbc.queryForObject("SELECT snapshot FROM agent_task WHERE task_id=?", String.class, id)).isEqualTo(legacy);
        assertThat(jdbc.queryForObject("SELECT event_sequence FROM agent_task WHERE task_id=?", Long.class, id)).isEqualTo(eventCount);
    }

    private void awaitState(String id, AgentTaskState expected) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        AgentTaskState state = null;
        while (System.currentTimeMillis() < deadline) {
            String json = mvc.perform(get("/api/agent/tasks/{id}", id)).andReturn().getResponse().getContentAsString();
            JsonNode node = mapper.readTree(json);
            state = AgentTaskState.valueOf(node.path("state").asText());
            if (state == expected) return;
            Thread.sleep(10);
        }
        assertThat(state).isEqualTo(expected);
    }
}
