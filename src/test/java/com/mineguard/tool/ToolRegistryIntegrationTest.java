package com.mineguard.tool;

import com.mineguard.device.IndustrialGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ToolRegistryIntegrationTest {
    @Autowired ToolRegistry registry;
    @Autowired IndustrialGateway gateway;

    @Test
    void discoversAllRequiredTools() {
        assertThat(registry.list()).extracting(Tool::name).containsExactlyInAnyOrder(
                "query_safety_events", "get_device_status", "list_detection_tasks",
                "start_detection_task", "stop_detection_task", "query_alert_statistics",
                "search_safety_knowledge", "create_inspection_plan", "verify_detection_task");
    }

    @Test
    void highRiskToolCannotRunWithoutBackendGrant() {
        gateway.stopDetectionTask("camera-03", "intrusion_detection");
        ToolResult result = registry.execute("start_detection_task",
                new ToolContext("direct-unapproved", false, Instant.now()),
                Map.of("cameraId", "camera-03", "algorithm", "intrusion_detection"));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolRegistry.APPROVAL_REQUIRED);
        assertThat(gateway.verifyDetectionTask("camera-03", "intrusion_detection", "STOPPED")).isTrue();
    }

    @Test
    void approvedHighRiskToolExecutes() {
        ToolResult result = registry.execute("start_detection_task",
                new ToolContext("direct-approved", true, Instant.now()),
                Map.of("cameraId", "camera-03", "algorithm", "intrusion_detection"));
        assertThat(result.success()).isTrue();
        assertThat(gateway.verifyDetectionTask("camera-03", "intrusion_detection", "RUNNING")).isTrue();
    }

    @Test
    void validatesRequiredAndUnknownArguments() {
        ToolResult missing = registry.execute("get_device_status", ToolContext.forTask("invalid-1"), Map.of());
        ToolResult unknown = registry.execute("get_device_status", ToolContext.forTask("invalid-2"),
                Map.of("deviceId", "camera-01", "admin", true));
        assertThat(missing.errorCode()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(unknown.errorMessage()).contains("unknown argument");
    }

    @Test
    void convertsGatewayExceptionIntoStructuredFailure() {
        ToolResult result = registry.execute("start_detection_task",
                new ToolContext("tool-error", true, Instant.now()),
                Map.of("cameraId", "camera-99", "algorithm", "personnel_violation"));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
    }
}
