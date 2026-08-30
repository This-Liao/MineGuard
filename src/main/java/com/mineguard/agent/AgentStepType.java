package com.mineguard.agent;

public enum AgentStepType {
    QUERY_EVENT("query_safety_events"),
    GET_DEVICE_STATUS("get_device_status"),
    LIST_DETECTION_TASKS("list_detection_tasks"),
    START_DETECTION_TASK("start_detection_task"),
    STOP_DETECTION_TASK("stop_detection_task"),
    QUERY_ALERT_STATISTICS("query_alert_statistics"),
    SEARCH_SAFETY_KNOWLEDGE("search_safety_knowledge"),
    CREATE_INSPECTION_PLAN("create_inspection_plan"),
    VERIFY_DETECTION_TASK("verify_detection_task");

    private final String toolName;

    AgentStepType(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
