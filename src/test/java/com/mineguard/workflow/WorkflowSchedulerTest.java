package com.mineguard.workflow;

import com.mineguard.config.MineGuardProperties;
import com.mineguard.config.RuntimeProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowSchedulerTest {
    private final JdbcAgentTaskStore store = mock(JdbcAgentTaskStore.class);
    private final AgentWorkflowEngine engine = mock(AgentWorkflowEngine.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final JdbcAgentTaskStore.Lease lease = new JdbcAgentTaskStore.Lease("task", "node", 1);

    private WorkflowScheduler scheduler(boolean enabled) {
        return new WorkflowScheduler(store, engine, executor,
                new RuntimeProperties(enabled, 30, 600, 3600, "test", "", ""),
                new MineGuardProperties(null, null, "", "", 1));
    }
    private void candidate() {
        when(store.candidates(2)).thenReturn(List.of("task", "second"));
        when(store.claim(eq("task"), anyString(), eq(30))).thenReturn(Optional.of(lease));
    }
    @Test void disabledAndStoppedSchedulersNeverClaim() {
        scheduler(false).dispatch();
        var scheduler = scheduler(true); scheduler.stop(); scheduler.dispatch(); scheduler.heartbeat();
        verifyNoInteractions(store, engine); verify(executor).shutdownNow();
    }
    @Test void capacityLimitsClaimsAndHeartbeatSurvivesTransientFailure() {
        candidate(); var scheduler = scheduler(true);
        scheduler.dispatch(); scheduler.dispatch();
        verify(store, times(1)).claim(eq("task"), anyString(), eq(30));
        verify(store, never()).claim(eq("second"), anyString(), anyInt());
        when(store.renew(lease, 30)).thenThrow(new IllegalStateException("临时断连")).thenReturn(true);
        assertThatCode(scheduler::heartbeat).doesNotThrowAnyException(); scheduler.heartbeat();
        verify(store, times(2)).renew(lease, 30);
    }
    @Test void rejectedSubmissionReleasesLeaseAndCapacity() {
        candidate(); when(store.candidates(2)).thenReturn(List.of("task"));
        doThrow(new RejectedExecutionException()).when(executor).submit(any(Runnable.class));
        var scheduler = scheduler(true); scheduler.dispatch(); scheduler.dispatch();
        verify(store, times(2)).release(lease);
        verifyNoInteractions(engine);
    }
    @Test void successfulAndExceptionalRunsBothFreeCapacity() {
        candidate(); var scheduler = scheduler(true);
        scheduler.dispatch();
        var job = ArgumentCaptor.forClass(Runnable.class); verify(executor).submit(job.capture());
        job.getValue().run(); scheduler.dispatch();
        verify(store, times(2)).claim(eq("task"), anyString(), eq(30));
        verify(executor, times(2)).submit(job.capture());
        doThrow(new IllegalStateException("故障注入")).when(engine).runLease(lease);
        assertThatThrownBy(() -> job.getValue().run()).hasMessage("故障注入");
        scheduler.dispatch(); verify(store, times(3)).claim(eq("task"), anyString(), eq(30));
    }
    @Test void leaseClaimLostDoesNotSubmitWork() {
        when(store.candidates(2)).thenReturn(List.of("task"));
        when(store.claim(eq("task"), anyString(), eq(30))).thenReturn(Optional.empty());
        scheduler(true).dispatch(); verifyNoInteractions(executor, engine);
    }
}
