package com.mineguard.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.config.DemoDataSeeder;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeterministicAgentModelClient implements AgentModelClient {
    private static final Pattern CAMERA = Pattern.compile("camera-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AREA = Pattern.compile("(?:[1-9]号采区|主运输巷|通风机房)");
    private final ObjectMapper mapper;

    public DeterministicAgentModelClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String createPlan(String userQuery, List<Map<String, Object>> availableTools, String correction) {
        String query = userQuery == null ? "" : userQuery.trim();
        String lower = query.toLowerCase(Locale.ROOT);
        List<PlanStep> steps = new ArrayList<>();
        String camera = match(CAMERA, lower);
        String area = Optional.ofNullable(match(AREA, query)).orElse("全矿区");
        String algorithm = algorithm(lower);
        Map<String, Object> filter = filterArgs(query, area);
        boolean start = containsAny(lower, "启动", "重新启动", "start", "resume");
        boolean stop = containsAny(lower, "停止", "关闭", "stop", "disable");
        boolean listDetection = containsAny(lower, "检测任务", "算法任务", "list detection", "list task");
        boolean device = camera != null && containsAny(lower, "状态", "设备", "摄像头", "camera", "在线", "离线");
        boolean gas = containsAny(lower, "瓦斯", "gas");
        boolean knowledge = containsAny(lower, "规程", "规范", "处置", "依据", "应急", "怎么办", "知识");
        boolean analyticSignal = containsAny(lower, "事件", "违规", "分析", "统计", "高频", "最近");
        boolean events = !start && !stop && (analyticSignal || (!knowledge && lower.contains("告警")));
        boolean inspection = containsAny(lower, "巡查计划", "巡检计划", "inspection plan");

        if (device || start || stop) {
            if (camera != null) steps.add(step(steps, AgentStepType.GET_DEVICE_STATUS, "查询设备当前状态", Map.of("deviceId", camera)));
            if (start || stop || listDetection) steps.add(step(steps, AgentStepType.LIST_DETECTION_TASKS, "检查现有检测任务", camera == null ? Map.of() : Map.of("cameraId", camera)));
        }
        if (events || gas) {
            Map<String, Object> eventArgs = new LinkedHashMap<>(filter);
            if (gas) eventArgs.put("eventType", "GAS_WARNING");
            steps.add(step(steps, AgentStepType.QUERY_EVENT, "查询结构化安全事件", eventArgs));
            steps.add(step(steps, AgentStepType.QUERY_ALERT_STATISTICS, "聚合告警分布", eventArgs));
        }
        if (events || gas || knowledge || inspection) {
            steps.add(step(steps, AgentStepType.SEARCH_SAFETY_KNOWLEDGE, "检索安全知识依据", Map.of("query", query, "topK", 5)));
        }
        if (inspection) {
            steps.add(step(steps, AgentStepType.CREATE_INSPECTION_PLAN, "创建可复核的巡查计划",
                    Map.of("area", area, "riskTopic", gas ? "GAS_WARNING" : "SAFETY_EVENT")));
        }
        if (start) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (camera != null) args.put("cameraId", camera);
            if (algorithm != null) args.put("algorithm", algorithm);
            steps.add(step(steps, AgentStepType.START_DETECTION_TASK, "启动检测任务（需人工审批）", args));
        }
        if (stop) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (camera != null) args.put("cameraId", camera);
            if (algorithm != null) args.put("algorithm", algorithm);
            steps.add(step(steps, AgentStepType.STOP_DETECTION_TASK, "停止检测任务（需人工审批）", args));
        }
        if (steps.isEmpty()) {
            return "{\"intent\":\"unsupported_request\",\"riskLevel\":\"LOW\",\"steps\":[]}";
        }
        RiskLevel risk = start || stop ? RiskLevel.HIGH : (inspection || gas ? RiskLevel.MEDIUM : RiskLevel.LOW);
        String intent = start ? "start_detection" : stop ? "stop_detection" : inspection ? "inspection_planning"
                : gas ? "gas_analysis" : events ? "event_analysis" : device ? "device_query" : "knowledge_query";
        try {
            return mapper.writeValueAsString(new AgentPlan(intent, risk, steps));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize deterministic plan", ex);
        }
    }

    @Override public String providerName() { return "deterministic"; }
    @Override public boolean realModel() { return false; }

    private PlanStep step(List<PlanStep> steps, AgentStepType type, String description, Map<String, Object> args) {
        return new PlanStep("step-" + (steps.size() + 1), type, description, args);
    }

    private Map<String, Object> filterArgs(String query, String area) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (!"全矿区".equals(area)) args.put("area", area);
        if (query.contains("24小时")) args.put("startTime", DemoDataSeeder.EVENT_ANCHOR.minus(24, ChronoUnit.HOURS).toString());
        else if (query.contains("一周") || query.contains("七天") || query.toLowerCase(Locale.ROOT).contains("7 day"))
            args.put("startTime", DemoDataSeeder.EVENT_ANCHOR.minus(7, ChronoUnit.DAYS).toString());
        if (query.contains("高等级") || query.contains("严重")) args.put("severity", "HIGH");
        if (query.contains("安全帽")) args.put("eventType", "NO_HELMET");
        else if (query.contains("入侵") || query.contains("越界")) args.put("eventType", "INTRUSION");
        else if (query.contains("吸烟")) args.put("eventType", "SMOKING");
        else if (query.contains("离线")) args.put("eventType", "DEVICE_OFFLINE");
        return args;
    }

    private String algorithm(String query) {
        if (containsAny(query, "intrusion", "入侵", "越界")) return "intrusion_detection";
        if (containsAny(query, "helmet", "安全帽")) return "no_helmet";
        if (containsAny(query, "personnel", "人员违规", "违规检测")) return "personnel_violation";
        if (containsAny(query, "smoking", "吸烟")) return "smoking_detection";
        return "personnel_violation";
    }

    private boolean containsAny(String value, String... needles) {
        return Arrays.stream(needles).anyMatch(value::contains);
    }

    private String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }
}
