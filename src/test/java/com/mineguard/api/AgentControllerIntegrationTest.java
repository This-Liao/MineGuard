package com.mineguard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.workflow.AgentTaskState;
import org.junit.jupiter.api.Test;
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

    @Test
    void createsAndReadsTaskThroughApi() throws Exception {
        String body = mvc.perform(post("/api/agent/tasks").contentType("application/json")
                        .content("{\"query\":\"查询安全事件处置规范\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).path("taskId").asText();
        mvc.perform(get("/api/agent/tasks/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(id));
        mvc.perform(get("/api/agent/tasks/{id}/events", id)).andExpect(status().isOk());
    }

    @Test
    void exposesApprovalAndSseEndpoints() throws Exception {
        String body = mvc.perform(post("/api/agent/tasks").contentType("application/json")
                        .content("{\"query\":\"启动 camera-08 的 no_helmet 检测\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).path("taskId").asText();
        awaitState(id, AgentTaskState.WAITING_APPROVAL);

        mvc.perform(get("/api/agent/tasks/{id}/stream", id))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        mvc.perform(post("/api/tasks/{id}/reject", id).contentType("application/json")
                        .content("{\"actor\":\"api-test\",\"reason\":\"reject test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approval.status").value("REJECTED"));
        awaitState(id, AgentTaskState.COMPLETED);
    }

    @Test
    void validatesBlankRequestAndMissingTask() throws Exception {
        mvc.perform(post("/api/agent/tasks").contentType("application/json").content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent/tasks/missing")).andExpect(status().isNotFound());
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
