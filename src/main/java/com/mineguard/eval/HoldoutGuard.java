package com.mineguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.security.Digests;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

/** 在任何付费请求前核验冻结版本、样本隔离及单轮运行约束。 */
public final class HoldoutGuard {
    public static final Path MANIFEST = Path.of("data/eval/holdout_v1_manifest.json");
    public static final Path CASES = Path.of("data/eval/agent_holdout_v1.json");
    private HoldoutGuard() {}

    public static JsonNode verify(Path root, ObjectMapper mapper) throws IOException {
        JsonNode manifest = mapper.readTree(root.resolve(MANIFEST).toFile());
        if (!"agent-holdout-v1".equals(manifest.path("suite").asText()) || manifest.path("caseCount").asInt() != 24
                || !manifest.path("sources").isObject() || manifest.path("sources").size() < 5) {
            throw new IllegalStateException("留出评测冻结清单无效");
        }
        Path base = root.toAbsolutePath().normalize();
        var fields = manifest.path("sources").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            Path file = base.resolve(entry.getKey()).normalize();
            if (!file.startsWith(base) || !textHash(file).equals(entry.getValue().asText())) {
                throw new IllegalStateException("冻结源文件已变化：" + entry.getKey());
            }
        }
        if (!textHash(root.resolve(CASES)).equals(manifest.path("caseSha256").asText())) {
            throw new IllegalStateException("留出题目或期望结果已变化");
        }
        JsonNode cases = mapper.readTree(root.resolve(CASES).toFile());
        if (!cases.isArray() || cases.size() != 24) throw new IllegalStateException("留出集分母必须为 24");
        Set<String> old = new HashSet<>();
        for (String name : new String[]{"agent_cases.json", "agent_supplemental_cases.json"}) {
            mapper.readTree(root.resolve("data/eval/" + name).toFile()).forEach(c -> old.add(normalize(c.path("query").asText())));
        }
        Set<String> seen = new HashSet<>(), ids = new HashSet<>();
        for (JsonNode row : cases) {
            String query = normalize(row.path("query").asText());
            if (query.isBlank() || old.contains(query) || !seen.add(query) || !ids.add(row.path("id").asText())) {
                throw new IllegalStateException("留出集包含空题、重复题或旧题");
            }
        }
        return manifest;
    }

    public static void requireFrozenModel(MineGuardProperties.Llm config, JsonNode manifest) {
        if (!manifest.path("model").asText().equals(config.model())
                || manifest.path("maxOutputTokens").asInt() != config.maxOutputTokens()
                || !manifest.path("thinking").asText().equals(config.thinking())
                || config.maxCalls() != manifest.path("maxCalls").asInt()) {
            throw new IllegalStateException("留出评测必须使用冻结的模型、输出上限、thinking 与 48 次调用额度");
        }
    }
    public static void claimAttempt(Path root, String runId) throws IOException {
        Path marker = root.resolve("data/runtime/holdout-v1/attempt.txt");
        Files.createDirectories(marker.getParent());
        try { Files.writeString(marker, runId, StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ex) { throw new IllegalStateException("本工作区已运行过 holdout-v1，禁止自动重复评测；请建立新批次"); }
    }
    public static String textHash(Path path) throws IOException { return Digests.sha256(Files.readString(path).replace("\r\n", "\n")); }
    private static String normalize(String query) { return query.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT); }
}
