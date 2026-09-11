package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.AgentPlan;
import com.mineguard.agent.AgentStepType;
import com.mineguard.agent.PlanningContract;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.llm.LangChain4jAgentModelClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 单次真实调用验收：只生成只读计划，不执行任何工具或工业写操作。 */
public final class LangChain4jSmokeApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private LangChain4jSmokeApplication() {}

    public static void main(String[] args) throws Exception {
        String key = require("OPENAI_API_KEY");
        String baseUrl = value("OPENAI_BASE_URL", "https://api.deepseek.com");
        String model = value("OPENAI_MODEL", "deepseek-v4-flash");
        int maxCalls = Integer.parseInt(value("MINEGUARD_LLM_MAX_CALLS", "2"));
        if (maxCalls < 1 || maxCalls > 2) throw new IllegalArgumentException("LangChain4j 冒烟验收最多允许 2 次调用");
        var config = new MineGuardProperties.Llm("langchain4j-openai-compatible", baseUrl, key, model,
                maxCalls, 2048, 60, "disabled");
        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        Path directory = Path.of("data/runtime/langchain4j-smoke", runId);
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("startedAt", Instant.now().toString());
        report.put("provider", "LangChain4j AI Services / OpenAI-compatible");
        report.put("baseUrl", URI.create(baseUrl).getScheme() + "://" + URI.create(baseUrl).getHost());
        report.put("model", model);
        report.put("requestLimit", maxCalls);
        report.put("planningContract", PlanningContract.VERSION);
        LangChain4jAgentModelClient client = null;
        try {
            client = new LangChain4jAgentModelClient(config, MAPPER);
            String query = "请检索安全帽的现场检查规范，只汇报知识依据。";
            List<Map<String, Object>> tools = List.of(Map.of(
                    "stepType", AgentStepType.SEARCH_SAFETY_KNOWLEDGE.name(),
                    "tool", AgentStepType.SEARCH_SAFETY_KNOWLEDGE.toolName(),
                    "description", "检索安全知识库并返回可溯源证据",
                    "category", "READ_ONLY",
                    "schema", Map.of("query", "非空字符串", "topK", "1 至 10 的整数")));
            AgentPlan plan = MAPPER.readValue(client.createPlan(query, tools, null), AgentPlan.class);
            List<String> errors = PlanningContract.validate(query, plan);
            if (plan.intent() == null || plan.intent().isBlank() || plan.steps().isEmpty()
                    || plan.steps().stream().anyMatch(step -> step.type() != AgentStepType.SEARCH_SAFETY_KNOWLEDGE)
                    || !errors.isEmpty()) {
                throw new IllegalStateException("真实模型生成的计划未通过只读业务契约");
            }
            report.put("query", query);
            report.put("plan", plan);
            report.put("status", "COMPLETED");
        } catch (Exception ex) {
            report.put("status", "ABORTED");
            report.put("error", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            if (client != null) report.put("usage", client.usageSnapshot());
            report.put("finishedAt", Instant.now().toString());
            var writer = MAPPER.writerWithDefaultPrettyPrinter();
            writer.writeValue(directory.resolve("report.json").toFile(), report);
            if ("COMPLETED".equals(report.get("status"))) {
                Path archive = Path.of("docs/eval/langchain4j-smoke-2026-09-12.json");
                Files.createDirectories(archive.getParent());
                writer.writeValue(archive.toFile(), report);
            }
            System.out.println("LangChain4j 真实调用报告：" + directory.toAbsolutePath());
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw new IllegalStateException(name + " 未配置或格式无效");
        }
        return value.trim();
    }

    private static String value(String name, String fallback) {
        return Objects.requireNonNullElse(System.getenv(name), fallback).trim();
    }
}
