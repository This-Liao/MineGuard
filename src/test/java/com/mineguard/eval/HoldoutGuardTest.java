package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HoldoutGuardTest {
    @TempDir Path output;
    @Test void frozenSourcesAndNewQueriesRemainUntouched() throws Exception {
        var manifest = HoldoutGuard.verify(Path.of(""), new ObjectMapper());
        assertThat(manifest.path("caseCount").asInt()).isEqualTo(24);
        assertThat(manifest.path("developerVisible").asBoolean()).isTrue();
    }
    @Test void blocksSecondAttemptAndNormalizesLineEndings() throws Exception {
        HoldoutGuard.claimAttempt(output, "first-run");
        assertThatThrownBy(() -> HoldoutGuard.claimAttempt(output, "second-run")).hasMessageContaining("禁止自动重复");
        assertThat(Files.readString(output.resolve("data/runtime/holdout-v1/attempt.txt"))).isEqualTo("first-run");
        Files.writeString(output.resolve("lf"), "正文\n下一行\n");
        Files.writeString(output.resolve("crlf"), "正文\r\n下一行\r\n");
        assertThat(HoldoutGuard.textHash(output.resolve("lf"))).isEqualTo(HoldoutGuard.textHash(output.resolve("crlf")));
    }
    @Test void rejectsChangedModelSettings() throws Exception {
        var manifest = new ObjectMapper().readTree(HoldoutGuard.MANIFEST.toFile());
        var valid = new MineGuardProperties.Llm("openai-compatible", "https://api.deepseek.com", "test-secret", "deepseek-v4-flash", 48, 2048, 60, "disabled");
        assertThatCode(() -> HoldoutGuard.requireFrozenModel(valid, manifest)).doesNotThrowAnyException();
        var invalid = new MineGuardProperties.Llm("openai-compatible", "https://api.deepseek.com", "test-secret", "different-model", 48, 2048, 60, "disabled");
        assertThatThrownBy(() -> HoldoutGuard.requireFrozenModel(invalid, manifest)).hasMessageContaining("冻结");
    }
}
