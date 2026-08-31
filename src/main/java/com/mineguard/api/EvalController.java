package com.mineguard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/eval")
public class EvalController {
    private final ObjectMapper mapper;
    public EvalController(ObjectMapper mapper) { this.mapper = mapper; }

    @GetMapping("/comparison")
    public Object comparison() {
        // 固定归档白名单，不接受客户端提供文件路径；历史基线永久保留。
        Path path = Path.of("docs/eval/deepseek-v2-2026-08-31.json");
        Object candidate = Map.of("status", "NOT_RUN", "message", "新版评测尚未归档");
        try { if (Files.exists(path)) candidate = mapper.readTree(path.toFile()); }
        catch (IOException ex) { throw new IllegalStateException("无法读取评测归档", ex); }
        return Map.of("baseline", real(), "candidate", candidate);
    }

    @GetMapping("/real")
    public Object real() {
        Path path = Path.of("docs/eval/deepseek-2026-08-31.json");
        if (!Files.exists(path)) return Map.of("status", "NOT_RUN", "message", "尚无归档的真实模型报告");
        try { return mapper.readTree(path.toFile()); }
        catch (IOException ex) { throw new IllegalStateException("无法读取真实模型归档", ex); }
    }

    @GetMapping("/latest")
    public Object latest() {
        Path path = Path.of("docs/eval/latest.json").toAbsolutePath().normalize();
        if (!Files.exists(path)) return Map.of("status", "NOT_RUN", "message", "Run scripts/run-eval first.");
        try { return mapper.readTree(path.toFile()); }
        catch (IOException ex) { throw new IllegalStateException("cannot read evaluation result", ex); }
    }
}
