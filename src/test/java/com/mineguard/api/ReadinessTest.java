package com.mineguard.api;

import com.mineguard.security.IdentityService;
import com.mineguard.security.TaskAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadinessTest {
    @Test void healthDoesNotClaimReadyBeforeStartupRunnersComplete() {
        var availability = mock(ApplicationAvailability.class);
        var controller = new AuthController(mock(IdentityService.class), new TaskAccessPolicy(), availability);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
    }
}
