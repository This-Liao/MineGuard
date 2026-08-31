package com.mineguard.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.approval.ApprovalStatus;
import com.mineguard.rag.Evidence;
import com.mineguard.tool.ToolExecutionRecord;
import com.mineguard.workflow.AgentTask;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.mineguard.agent.AgentReport.*;

/** 将已完成工具回执整理成中文，不新增模型请求、查询或工业操作。 */
@Component
public class AgentReportComposer {
    private static final String VERSION = "grounded-report-v1";
    private static final Map<String, String> EVENTS = Map.of("NO_HELMET", "未佩戴安全帽", "INTRUSION", "区域入侵",
            "SMOKING", "吸烟", "DEVICE_OFFLINE", "设备离线", "GAS_WARNING", "瓦斯告警", "PERSONNEL_STAY", "人员滞留", "LOW_LIGHT", "低照度");
    private static final Map<String, String> STATUS = Map.of("ONLINE", "在线", "OFFLINE", "离线", "DEGRADED", "性能降级",
            "UNKNOWN", "未知", "RUNNING", "运行中", "STOPPED", "已停止");
    private static final Map<String, String> ALGORITHMS = Map.of("intrusion_detection", "区域入侵检测", "no_helmet", "安全帽检测",
            "personnel_violation", "人员违规检测", "smoking_detection", "吸烟检测");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private final ObjectMapper mapper;

    public AgentReportComposer(ObjectMapper mapper) { this.mapper = mapper; }

    public AgentResult result(AgentTask task, List<String> executed, List<String> verification) {
        AgentReport report = compose(task);
        List<String> findings = report.sections().stream().filter(s -> s.key().equals("observations"))
                .flatMap(s -> s.statements().stream()).map(Statement::text).toList();
        List<String> actions = report.sections().stream().filter(s -> s.key().equals("references"))
                .flatMap(s -> s.statements().stream()).map(Statement::text).toList();
        return new AgentResult(task.getTaskId(), report.summary(), task.getPlan().riskLevel(), findings, actions,
                task.getEvidence(), executed, verification, List.of(), report);
    }

    /** 历史任务按原始回执即时生成展示结果，不改数据库中的历史任务和事件。 */
    public AgentReport existing(AgentTask task) {
        if (task.getResult() == null) throw new IllegalStateException("任务尚无完成结果，请等待执行结束");
        return task.getResult().report() != null ? task.getResult().report() : compose(task);
    }

    private AgentReport compose(AgentTask task) {
        List<Statement> observations = new ArrayList<>(), references = new ArrayList<>(), operations = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        List<ToolExecutionRecord> calls = task.getToolCalls();
        boolean hasEvents = false, hasKnowledge = false;
        for (int index = 0; index < calls.size(); index++) {
            ToolExecutionRecord call = calls.get(index);
            if (!call.result().success()) {
                notes.add("有工具调用未成功，不能据此推断相关操作已完成；请核对调用记录。");
                continue;
            }
            JsonNode data = mapper.valueToTree(call.result().data());
            if (data == null || data.isNull()) continue;
            switch (call.toolName()) {
                case "query_safety_events" -> {
                    hasEvents = true;
                    int source = toolSource(citations, index, "安全事件明细");
                    Long total = count(data.get("total"));
                    String subject = scope(mapper.valueToTree(call.args()));
                    if (total != null) observations.add(statement(subject + (total == 0 ? "未查询到符合条件的事件。未检出不等于现场不存在风险。" : "共查询到 " + total + " 条安全事件。"), source));
                    JsonNode events = data.path("events");
                    if (events.isArray()) {
                        boolean complete = !data.path("truncated").asBoolean(false) && total != null && events.size() == total;
                        long severe = 0, unresolvedSevere = 0;
                        for (JsonNode event : events) {
                            if (Set.of("HIGH", "CRITICAL").contains(event.path("severity").asText())) {
                                severe++;
                                if (Set.of("OPEN", "ACKNOWLEDGED", "IN_PROGRESS").contains(event.path("status").asText())) unresolvedSevere++;
                            }
                        }
                        if (severe > 0) observations.add(statement((complete ? "上述事件中" : "当前返回的 " + events.size() + " 条明细中") + "，高等级或严重事件 " + severe + " 条；其中明确标记为待处理或处理中的有 " + unresolvedSevere + " 条。事件等级与是否已处置是不同维度。", source));
                        if (!complete) notes.add("事件明细仅是返回样本，涉及等级和处置状态的统计不外推到未返回的记录。");
                    }
                }
                case "query_alert_statistics" -> {
                    hasEvents = true;
                    int source = toolSource(citations, index, "告警分布统计");
                    Long total = count(data.get("total"));
                    List<Map.Entry<String, Long>> counts = new ArrayList<>();
                    data.path("byType").fields().forEachRemaining(e -> { Long n = count(e.getValue()); if (n != null && n > 0) counts.add(Map.entry(e.getKey(), n)); });
                    counts.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
                    if (!counts.isEmpty()) {
                        String distribution = counts.stream().map(e -> eventName(e.getKey()) + " " + e.getValue() + " 条").collect(Collectors.joining("、"));
                        observations.add(statement(scope(data.path("filters").isObject() ? data.path("filters") : mapper.valueToTree(call.args())) + "的告警类型分布为：" + distribution + "。", source));
                        long max = counts.getFirst().getValue();
                        String leaders = counts.stream().filter(e -> e.getValue() == max).map(e -> eventName(e.getKey())).collect(Collectors.joining("、"));
                        String share = total != null && total > 0 && counts.stream().mapToLong(Map.Entry::getValue).sum() == total
                                ? "，" + (counts.stream().filter(e -> e.getValue() == max).count() > 1 ? "各" : "") + "占 " + String.format(Locale.ROOT, "%.1f%%", max * 100d / total) : "";
                        observations.add(statement("出现次数最多的是" + leaders + "（" + max + " 条" + share + "）。频次仅反映本次查询分布，不等同于风险等级或事故原因。", source));
                    } else if (total != null && total == 0) observations.add(statement(scope(data.path("filters")) + "的聚合统计为 0 条。", source));
                    else notes.add("统计回执缺少可用的分类数量，未据此编造分布或百分比。");
                }
                case "get_device_status" -> {
                    int source = toolSource(citations, index, "设备状态回执");
                    observations.add(statement("设备 " + data.path("deviceId").asText(String.valueOf(call.args().get("deviceId"))) + " 在查询时的状态为“" + status(data.path("status").asText("UNKNOWN")) + "”。", source));
                }
                case "list_detection_tasks" -> {
                    int source = toolSource(citations, index, "检测任务列表");
                    if (data.isArray()) {
                        String target = call.args().get("cameraId") instanceof String camera ? "摄像头 " + camera : "本次范围";
                        observations.add(statement(target + "返回 " + data.size() + " 个检测任务" + (data.isEmpty() ? "。" : "：" + elements(data).stream().limit(8).map(e -> algorithm(e.path("algorithm").asText()) + "（" + status(e.path("status").asText("UNKNOWN")) + "）").collect(Collectors.joining("、")) + (data.size() > 8 ? "等。" : "。")), source));
                    }
                }
                case "create_inspection_plan" -> {
                    int source = toolSource(citations, index, "巡查计划回执");
                    boolean draft = "DRAFT".equals(data.path("status").asText());
                    operations.add(statement("已为" + data.path("area").asText("本次区域") + "生成巡查" + (draft ? "计划草案" : "计划记录") + "（" + data.path("planId").asText("编号未返回") + "）" + (draft ? "，尚不表示已派发或完成现场巡查。" : "；实际状态以回执为准。"), source));
                    String items = elements(data.path("items")).stream().filter(JsonNode::isTextual).map(JsonNode::asText).collect(Collectors.joining("；"));
                    if (!items.isBlank()) operations.add(statement("计划中的检查项为：" + items + "。", source));
                }
                case "start_detection_task", "stop_detection_task" -> {
                    int source = toolSource(citations, index, "检测操作回执");
                    operations.add(statement("已收到摄像头 " + call.args().get("cameraId") + " 的" + algorithm(String.valueOf(call.args().get("algorithm")))
                            + (call.toolName().startsWith("start") ? "启动" : "停止") + "成功回执；是否达到目标状态，以后续独立核验为准。", source));
                }
                case "verify_detection_task" -> {
                    int source = toolSource(citations, index, "独立状态核验");
                    if (data.path("verified").asBoolean(false)) operations.add(statement("独立核验确认：" + call.args().get("cameraId") + " 的" + algorithm(String.valueOf(call.args().get("algorithm"))) + "在核验时为“" + status(String.valueOf(call.args().get("expectedStatus"))) + "”。", source));
                }
                case "search_safety_knowledge" -> hasKnowledge = true;
                default -> notes.add("另有工具回执未转换为文字汇报，可在调用记录中查看。");
            }
        }
        // 只引用本任务实际检索并保存的片段；同文档同片段只编号一次。
        Set<String> seen = new HashSet<>();
        if (hasKnowledge) for (Evidence evidence : task.getEvidence()) {
            if (evidence.documentId() == null || evidence.chunkId() == null || evidence.content() == null
                    || !seen.add(evidence.documentId() + "\u0000" + evidence.chunkId())) continue;
            String excerpt = excerpt(evidence.content());
            if (excerpt.isBlank()) continue;
            int id = citations.size() + 1;
            citations.add(new Citation(id, "KNOWLEDGE", evidence.title(), evidence.documentId(), evidence.chunkId(),
                    evidence.score(), evidence.content(), null));
            references.add(statement("关于“" + evidence.title() + "”，检索资料提到：“" + excerpt + "”", id));
            if (references.size() >= 3) break;
        }
        if (hasKnowledge && references.isEmpty()) notes.add("本次未取得可引用的知识片段，因此不补写未经来源支持的处置建议。");
        if (!references.isEmpty()) notes.add("处置参考为检索片段摘录，适用性须现场复核；相关度分数不是结论置信度，也不证明事故原因。");
        if (task.getEvidence().stream().anyMatch(e -> e.content() != null && (e.content().contains("Synthetic Demo Data") || e.content().contains("合成") || e.content().contains("仅供软件演示"))))
            notes.add("本任务引用的知识资料包含合成演示内容，不替代正式安全规程。");
        boolean rejected = task.getApproval() != null && task.getApproval().status() == ApprovalStatus.REJECTED;
        String summary = rejected ? "操作已被拒绝，未执行本次高风险变更。以下仅汇报审批前已取得的信息。"
                : hasEvents ? "已完成本次安全事件分析，以下是主要发现与可追溯的处置参考。"
                : !operations.isEmpty() ? "本次任务的操作与核验结果如下。" : hasKnowledge ? "已整理本次检索到的相关资料，供你核对参考。" : "本次查询已完成，结果如下。";
        List<Section> sections = new ArrayList<>();
        if (!observations.isEmpty()) sections.add(new Section("observations", "本次发现", List.copyOf(observations)));
        if (!operations.isEmpty()) sections.add(new Section("operations", "已执行事项", List.copyOf(operations)));
        if (!references.isEmpty()) sections.add(new Section("references", "处置参考", List.copyOf(references)));
        if (sections.isEmpty()) notes.add("已有记录不足以生成事实汇报，请查看原始工具回执和任务状态。");
        Set<Integer> used = sections.stream().flatMap(s -> s.statements().stream()).flatMap(s -> s.citationIds().stream()).collect(Collectors.toSet());
        return new AgentReport(VERSION, summary, List.copyOf(sections), citations.stream().filter(c -> used.contains(c.id())).toList(), List.copyOf(notes));
    }

    private static Statement statement(String text, int source) { return new Statement(text, List.of(source)); }
    private static int toolSource(List<Citation> citations, int index, String title) {
        int id = citations.size() + 1;
        citations.add(new Citation(id, "TOOL", title, null, null, null, null, index));
        return id;
    }
    private static Long count(JsonNode node) { return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 ? node.longValue() : null; }
    private static String eventName(String code) { return EVENTS.getOrDefault(code, code); }
    private static String status(String code) { return STATUS.getOrDefault(code, code); }
    private static String algorithm(String code) { return ALGORITHMS.getOrDefault(code, code); }
    private static List<JsonNode> elements(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node.isArray()) node.forEach(values::add);
        return values;
    }
    private static String scope(JsonNode args) {
        String area = args.path("area").asText("");
        String value = area.isBlank() ? "本次查询范围内" : area;
        String start = time(args.path("startTime").asText("")), end = time(args.path("endTime").asText(""));
        if (!start.isBlank() || !end.isBlank()) value += "（" + (start.isBlank() ? "未指定起始" : start) + " 至 " + (end.isBlank() ? "未指定截止" : end) + "，北京时间）";
        return value;
    }
    private static String time(String value) {
        if (value.isBlank()) return "";
        try { return TIME.format(Instant.parse(value)); } catch (RuntimeException ex) { return "时间格式不可识别"; }
    }
    private static String excerpt(String content) {
        // 不把标题或演示声明当作处置建议；保留完整句子，原始片段另存作溯源。
        String body = content.lines().filter(line -> !line.stripLeading().startsWith("#"))
                .filter(line -> !line.contains("Synthetic Demo Data") && !line.contains("仅供软件演示"))
                .map(String::trim).filter(line -> !line.isBlank()).collect(Collectors.joining(" "));
        StringBuilder result = new StringBuilder();
        for (String sentence : body.split("(?<=[。！？])")) {
            if (result.length() > 0 && result.length() + sentence.length() > 200) break;
            if (sentence.length() > 360 && result.isEmpty()) return "该片段较长，请展开引用查看完整原文。";
            result.append(sentence);
            if (result.length() >= 140) break;
        }
        return result.toString().trim();
    }
}
