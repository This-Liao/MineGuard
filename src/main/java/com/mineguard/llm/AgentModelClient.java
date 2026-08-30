package com.mineguard.llm;

import java.util.List;
import java.util.Map;

public interface AgentModelClient {
    String createPlan(String userQuery, List<Map<String, Object>> availableTools, String correction);
    String providerName();
    boolean realModel();
}
