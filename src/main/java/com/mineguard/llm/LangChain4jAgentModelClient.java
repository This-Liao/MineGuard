package com.mineguard.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.AgentPlan;
import com.mineguard.agent.AgentStepType;
import com.mineguard.agent.PlanStep;
import com.mineguard.agent.PlanningContract;
import com.mineguard.agent.RiskLevel;
import com.mineguard.config.MineGuardProperties;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用 LangChain4j AI Services 调用 OpenAI-compatible 模型并生成类型化计划。 */
public final class LangChain4jAgentModelClient implements AgentModelClient {
    private final MineGuardProperties.Llm config;
    private final ObjectMapper mapper;
    private final PlanningAiService service;
    private final ModelUsageRecorder usage;

    public LangChain4jAgentModelClient(MineGuardProperties.Llm config, ObjectMapper mapper) {
        this(config, mapper, createService(config));
    }

    LangChain4jAgentModelClient(MineGuardProperties.Llm config, ObjectMapper mapper, PlanningAiService service) {
        validate(config);
        this.config = config;
        this.mapper = mapper;
        this.service = service;
        this.usage = new ModelUsageRecorder(config.maxCalls());
    }

    @Override
    public String createPlan(String userQuery, List<Map<String, Object>> availableTools, String correction) {
        int number = usage.reserve();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        int status = 0;
        String outcome = "STRUCTURED_OUTPUT_ERROR";
        ModelUsageRecorder.Tokens tokens = ModelUsageRecorder.Tokens.unknown();
        try {
            String request = mapper.writeValueAsString(Map.of(
                    "query", userQuery,
                    "referenceTimeUtc", Instant.now().toString(),
                    "availableTools", availableTools,
                    "correction", correction == null ? "" : correction));
            Result<GeneratedPlan> result = service.generate(request);
            status = 200;
            tokens = tokens(result.tokenUsage());
            if (result.finishReason() != null && result.finishReason() != FinishReason.STOP) {
                outcome = "INCOMPLETE_RESPONSE";
                throw new IllegalStateException("模型回复未正常结束，请检查输出 Token 上限或服务状态");
            }
            AgentPlan plan = toAgentPlan(result.content());
            outcome = "SUCCESS";
            return mapper.writeValueAsString(plan);
        } catch (HttpException ex) {
            status = ex.statusCode();
            outcome = "HTTP_ERROR";
            // 服务端正文可能包含用户输入，因此不拼接原异常消息。
            throw new IllegalStateException("模型请求返回 HTTP " + status + "，未自动重试");
        } catch (LangChain4jException ex) {
            status = mappedStatus(ex);
            outcome = status == 0 ? "CLIENT_ERROR" : "HTTP_ERROR";
            if (status != 0) throw new IllegalStateException("模型请求返回 HTTP " + status + "，未自动重试");
            throw new IllegalStateException("LangChain4j 模型调用或结构化解析失败，用量可能不完整");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("模型请求或结构化计划序列化失败");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            outcome = "CLIENT_ERROR";
            // 不传播第三方异常正文，避免响应片段进入任务 Trace。
            throw new IllegalStateException("LangChain4j 模型调用或结构化解析失败，用量可能不完整");
        } finally {
            usage.complete(number, startedAt, Math.max(0, (System.nanoTime() - start) / 1_000_000),
                    status, outcome, tokens);
        }
    }

    public ModelUsageRecorder.Snapshot usageSnapshot() {
        return usage.snapshot();
    }

    @Override
    public String providerName() {
        return "langchain4j-openai-compatible:" + config.model();
    }

    @Override
    public boolean realModel() {
        return true;
    }

    private AgentPlan toAgentPlan(GeneratedPlan generated) {
        if (generated == null) throw new IllegalStateException("模型未返回结构化计划");
        List<PlanStep> steps = generated.steps() == null ? List.of() : generated.steps().stream()
                .map(step -> new PlanStep(step.id(), step.type(), step.description(), arguments(step.args())))
                .toList();
        return new AgentPlan(generated.intent(), generated.riskLevel(), steps);
    }

    private Map<String, Object> arguments(GeneratedArguments args) {
        if (args == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "area", args.area());
        put(values, "eventType", args.eventType());
        put(values, "startTime", args.startTime());
        put(values, "endTime", args.endTime());
        put(values, "severity", args.severity());
        put(values, "query", args.query());
        put(values, "topK", args.topK());
        put(values, "cameraId", args.cameraId());
        put(values, "deviceId", args.deviceId());
        put(values, "algorithm", args.algorithm());
        put(values, "expectedStatus", args.expectedStatus());
        put(values, "riskTopic", args.riskTopic());
        return Map.copyOf(values);
    }

    private void put(Map<String, Object> values, String key, Object value) {
        if (value instanceof String text && text.isBlank()) return;
        if (value != null) values.put(key, value);
    }

    private static PlanningAiService createService(MineGuardProperties.Llm config) {
        validate(config);
        Map<String, Object> custom = config.thinking() == null || config.thinking().isBlank()
                ? Map.of()
                : Map.of("thinking", Map.of("type", config.thinking()));
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl(config.baseUrl()))
                .apiKey(config.apiKey())
                .modelName(config.model())
                .temperature(0.0)
                .maxTokens(config.maxOutputTokens())
                .responseFormat("json_object")
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .maxRetries(0)
                .returnThinking(false)
                .customParameters(custom)
                .logRequests(false)
                .logResponses(false)
                .build();
        return AiServices.create(PlanningAiService.class, model);
    }

    private static String normalizeBaseUrl(String raw) {
        URI base;
        try {
            base = URI.create(raw == null ? "" : raw.replaceAll("/+$", ""));
        } catch (RuntimeException ex) {
            throw invalidBaseUrl();
        }
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]")
                .contains(base.getHost() == null ? "" : base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || (loopback && "http".equals(base.getScheme())))) {
            throw invalidBaseUrl();
        }
        String path = base.getPath();
        return path == null || path.isBlank() || "/".equals(path) ? base + "/v1" : base.toString();
    }

    private static IllegalArgumentException invalidBaseUrl() {
        return new IllegalArgumentException("模型基础地址须为不含凭据的 HTTPS URL；仅本机离线测试允许 HTTP");
    }

    private static void validate(MineGuardProperties.Llm config) {
        if (config.apiKey() == null || config.apiKey().isBlank()
                || config.apiKey().contains("\n") || config.apiKey().contains("\r")) {
            throw new IllegalStateException("真实模型需要配置有效的 OPENAI_API_KEY");
        }
        if (config.model() == null || config.model().isBlank()
                || config.maxOutputTokens() < 1 || config.requestTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("模型 ID、输出 Token 上限和请求超时必须有效");
        }
        if (config.thinking() != null && !config.thinking().isBlank()
                && !Set.of("enabled", "disabled").contains(config.thinking())) {
            throw new IllegalArgumentException("thinking 只能留空或设置为 enabled/disabled");
        }
        normalizeBaseUrl(config.baseUrl());
    }

    private static ModelUsageRecorder.Tokens tokens(TokenUsage usage) {
        if (usage == null) return ModelUsageRecorder.Tokens.unknown();
        return new ModelUsageRecorder.Tokens(number(usage.inputTokenCount()), number(usage.outputTokenCount()),
                number(usage.totalTokenCount()), null, null, null);
    }

    private static Long number(Integer value) {
        return value == null || value < 0 ? null : value.longValue();
    }

    private static int mappedStatus(LangChain4jException ex) {
        if (ex instanceof AuthenticationException) return 401;
        if (ex instanceof ModelNotFoundException) return 404;
        if (ex instanceof RateLimitException) return 429;
        if (ex instanceof InvalidRequestException) return 400;
        if (ex instanceof InternalServerException) return 500;
        return 0;
    }

    interface PlanningAiService {
        @SystemMessage(PlanningContract.SYSTEM_PROMPT)
        @UserMessage("{{request}}")
        Result<GeneratedPlan> generate(@V("request") String request);
    }

    public record GeneratedPlan(
            @Description("用户意图的简短中文概述") String intent,
            @Description("计划整体风险，只能为 LOW、MEDIUM 或 HIGH") RiskLevel riskLevel,
            @Description("按执行顺序排列的步骤") List<GeneratedStep> steps) {}

    public record GeneratedStep(
            @Description("步骤 ID，例如 s1") String id,
            @Description("现有 AgentStepType 枚举值") AgentStepType type,
            @Description("该步骤的中文说明") String description,
            @Description("工具参数；没有参数时返回空对象") GeneratedArguments args) {}

    /** 固定字段避免模型生成任意键；转换后仍由现有 PlanningContract 做业务校验。 */
    public record GeneratedArguments(
            String area,
            String eventType,
            String startTime,
            String endTime,
            String severity,
            String query,
            Integer topK,
            String cameraId,
            String deviceId,
            String algorithm,
            String expectedStatus,
            String riskTopic) {}
}
