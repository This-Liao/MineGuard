package com.mineguard.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.contract.IndustrialContractServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HttpIndustrialGatewayTest {
    private IndustrialContractServer server;
    private HttpIndustrialGateway gateway;
    private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final String token = "offline-contract-token-2026";
    @BeforeEach void start() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:contract_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        server = new IndustrialContractServer(jdbc, mapper, token, 0); server.start(); gateway = client(token);
    }
    private HttpIndustrialGateway client(String key) { return new HttpIndustrialGateway("http://127.0.0.1:" + server.port(), key, "camera-03,camera-08,camera-17", "intrusion_detection,no_helmet,personnel_violation", mapper); }
    @AfterEach void close() { server.close(); }
    @Test void authenticatesAndQueriesWithoutInferringStatusFromConfiguration() {
        assertThat(gateway.getDeviceStatus("camera-03")).isEqualTo(DeviceStatus.ONLINE);
        assertThat(gateway.getDeviceStatus("camera-21")).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(gateway.getDeviceStatus("camera-99")).isEqualTo(DeviceStatus.UNKNOWN);
        assertThat(gateway.listDetectionTasks("camera-08")).hasSize(1);
        assertThatThrownBy(() -> client("incorrect-token-2026").getDeviceStatus("camera-03")).hasMessageContaining("401");
    }
    @Test void startStopAreIdempotentAndIndependentlyVerified() {
        var first = gateway.startDetectionTask("camera-03", "intrusion_detection", "start-1");
        var second = gateway.startDetectionTask("camera-03", "intrusion_detection", "start-1");
        assertThat(second).isEqualTo(first); assertThat(server.operationCount()).isEqualTo(1);
        assertThat(gateway.verifyDetectionTask("camera-03", "intrusion_detection", "RUNNING")).isTrue();
        gateway.stopDetectionTask("camera-03", "intrusion_detection", "stop-1");
        assertThat(server.operationCount()).isEqualTo(2);
        assertThat(gateway.verifyDetectionTask("camera-03", "intrusion_detection", "STOPPED")).isTrue();
        assertThat(gateway.verifyDetectionTask("camera-03", "intrusion_detection", "RUNNING")).isFalse();
    }
    @Test void alteredParametersWithSameKeyAreRejected() {
        gateway.startDetectionTask("camera-03", "intrusion_detection", "collision");
        assertThatThrownBy(() -> gateway.startDetectionTask("camera-08", "no_helmet", "collision")).hasMessageContaining("409");
        assertThat(server.operationCount()).isEqualTo(1);
    }
    @Test void unknownResponseKeepsPersistentReceiptAndDoesNotRetry() throws Exception {
        server.loseNextWriteResponse();
        assertThatThrownBy(() -> gateway.startDetectionTask("camera-03", "intrusion_detection", "lost-ack")).isInstanceOf(IndustrialOutcomeUnknownException.class);
        assertThat(server.operationCount()).isEqualTo(1);
        server.close(); server = new IndustrialContractServer(jdbc, mapper, token, 0); server.start(); gateway = client(token);
        assertThat(gateway.operationReceipt("lost-ack")).isPresent().get().extracting(DetectionTask::status).isEqualTo("RUNNING");
        assertThat(gateway.operationReceipt("missing")).isEmpty();
        assertThat(server.operationCount()).isEqualTo(1);
    }
    @Test void localWhitelistPreventsUnapprovedTargets() {
        assertThatThrownBy(() -> gateway.startDetectionTask("camera-99", "intrusion_detection", "no")).hasMessageContaining("白名单");
        assertThatThrownBy(() -> gateway.stopDetectionTask("camera-03", "unknown", "no")).hasMessageContaining("白名单");
        assertThat(server.operationCount()).isZero();
    }
}
