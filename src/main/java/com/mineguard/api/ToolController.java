package com.mineguard.api;

import com.mineguard.tool.Tool;
import com.mineguard.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class ToolController {
    private final ToolRegistry registry;
    public ToolController(ToolRegistry registry) { this.registry = registry; }

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.list().stream().map(this::view).toList();
    }

    private Map<String, Object> view(Tool tool) {
        return Map.of("name", tool.name(), "description", tool.description(), "category", tool.category(),
                "schema", tool.schema().asJsonSchema());
    }
}
