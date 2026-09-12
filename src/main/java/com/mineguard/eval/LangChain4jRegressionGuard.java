package com.mineguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** 在真实调用前锁定适配器回归的题集、实现版本、模型参数和单轮运行次数。 */
public final class LangChain4jRegressionGuard {
    public static final Path MANIFEST = Path.of("data/eval/langchain4j_regression_v2_manifest.json");
    public static final Path CASES = Path.of("data/eval/agent_holdout_v1.json");
    private static final Path ATTEMPT = Path.of("data/runtime/langchain4j-regression-v2/attempt.txt");

    private LangChain4jRegressionGuard() {}

    public static JsonNode verify(Path root, ObjectMapper mapper) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        JsonNode manifest = mapper.readTree(base.resolve(MANIFEST).toFile());
        if (!"langchain4j-adapter-regression-v2".equals(manifest.path("suite").asText())
                || manifest.path("caseCount").asInt() != 24
                || !manifest.path("developerVisible").asBoolean()
                || !manifest.path("sources").isObject()
                || manifest.path("sources").size() < 7) {
            throw new IllegalStateException("LangChain4j 回归冻结清单无效");
        }
        var fields = manifest.path("sources").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            Path file = base.resolve(entry.getKey()).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)
                    || !HoldoutGuard.textHash(file).equals(entry.getValue().asText())) {
                throw new IllegalStateException("LangChain4j 回归冻结源文件已变化：" + entry.getKey());
            }
        }
        if (!HoldoutGuard.textHash(base.resolve(CASES)).equals(manifest.path("caseSha256").asText())) {
            throw new IllegalStateException("LangChain4j 回归题目或期望结果已变化");
        }
        JsonNode cases = mapper.readTree(base.resolve(CASES).toFile());
        Set<String> ids = new HashSet<>();
        if (!cases.isArray() || cases.size() != 24) throw new IllegalStateException("回归集分母必须为 24");
        for (JsonNode row : cases) {
            if (row.path("query").asText().isBlank() || !ids.add(row.path("id").asText())) {
                throw new IllegalStateException("回归集包含空题或重复 ID");
            }
        }
        return manifest;
    }

    public static void requireFrozenModel(MineGuardProperties.Llm config, JsonNode manifest) {
        if (!manifest.path("model").asText().equals(config.model())
                || manifest.path("maxOutputTokens").asInt() != config.maxOutputTokens()
                || !manifest.path("thinking").asText().equals(config.thinking())
                || manifest.path("maxCalls").asInt() != config.maxCalls()) {
            throw new IllegalStateException("回归必须使用冻结的模型、输出上限、thinking 与调用额度");
        }
    }

    public static void claimAttempt(Path root, String runId) throws IOException {
        Path marker = root.toAbsolutePath().normalize().resolve(ATTEMPT);
        Files.createDirectories(marker.getParent());
        try {
            Files.writeString(marker, runId, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ex) {
            throw new IllegalStateException("本工作区已完成本批次调用声明，禁止自动重跑或挑选结果");
        }
    }
}
