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

    @GetMapping("/latest")
    public Object latest() {
        Path path = Path.of("docs/eval/latest.json").toAbsolutePath().normalize();
        if (!Files.exists(path)) return Map.of("status", "NOT_RUN", "message", "Run scripts/run-eval first.");
        try { return mapper.readTree(path.toFile()); }
        catch (IOException ex) { throw new IllegalStateException("cannot read evaluation result", ex); }
    }
}
