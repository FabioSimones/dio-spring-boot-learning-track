package dio.budgeting;

import dio.budgeting.infrastructure.idempotency.IdempotencyCleanupScheduler;
import dio.budgeting.infrastructure.idempotency.IdempotencyCleanupService;
import dio.budgeting.infrastructure.observability.AiObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for IdempotencyCleanupScheduler (TASK-010). {@code run()} is called
 * directly - never via a real {@code @Scheduled} clock/thread, so this suite
 * never depends on wall-clock timing. A separate assertion confirms the
 * scheduler bean itself is absent from the test application context, proving
 * app.idempotency.cleanup-enabled=false (src/test/resources/application-test.properties)
 * actually disables it via @ConditionalOnProperty - no automatic execution
 * ever races with other tests. No OpenAI call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class IdempotencyCleanupSchedulerTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void schedulerBean_isAbsentWhenCleanupDisabled() {
        assertThatThrownBy(() -> applicationContext.getBean(IdempotencyCleanupScheduler.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void run_recoversBeforeCleaningUp_andLogsAggregateCounts() {
        var cleanupService = mock(IdempotencyCleanupService.class);
        when(cleanupService.recoverAbandonedProcessing()).thenReturn(3);
        when(cleanupService.cleanupExpired()).thenReturn(25);
        var scheduler = new IdempotencyCleanupScheduler(cleanupService, new AiObservability(new SimpleMeterRegistry()));

        scheduler.run();

        var order = inOrder(cleanupService);
        order.verify(cleanupService).recoverAbandonedProcessing();
        order.verify(cleanupService).cleanupExpired();
    }

    @Test
    void run_neverThrows_evenWhenNothingToDo() {
        var cleanupService = mock(IdempotencyCleanupService.class);
        when(cleanupService.recoverAbandonedProcessing()).thenReturn(0);
        when(cleanupService.cleanupExpired()).thenReturn(0);
        var scheduler = new IdempotencyCleanupScheduler(cleanupService, new AiObservability(new SimpleMeterRegistry()));

        scheduler.run();

        assertThat(true).isTrue(); // reaching here means no exception was thrown
    }

    // --- TASK-011: cleanup metrics ---

    @Test
    void run_recordsRecoveredAndDeletedCounters_noIdsOrKeys() {
        var cleanupService = mock(IdempotencyCleanupService.class);
        when(cleanupService.recoverAbandonedProcessing()).thenReturn(3);
        when(cleanupService.cleanupExpired()).thenReturn(25);
        var meterRegistry = new SimpleMeterRegistry();
        var scheduler = new IdempotencyCleanupScheduler(cleanupService, new AiObservability(meterRegistry));

        scheduler.run();

        var recovered = meterRegistry.find("budgeting.idempotency.cleanup")
                .tag("action", "recovered").tag("result", "success").counter();
        var deleted = meterRegistry.find("budgeting.idempotency.cleanup")
                .tag("action", "deleted").tag("result", "success").counter();
        assertThat(recovered).isNotNull();
        assertThat(recovered.count()).isEqualTo(3.0);
        assertThat(deleted).isNotNull();
        assertThat(deleted.count()).isEqualTo(25.0);
        assertThat(recovered.getId().getTags()).hasSize(2);
    }

    @Test
    void run_withNothingToDo_recordsNoCounterIncrement() {
        var cleanupService = mock(IdempotencyCleanupService.class);
        when(cleanupService.recoverAbandonedProcessing()).thenReturn(0);
        when(cleanupService.cleanupExpired()).thenReturn(0);
        var meterRegistry = new SimpleMeterRegistry();
        var scheduler = new IdempotencyCleanupScheduler(cleanupService, new AiObservability(meterRegistry));

        scheduler.run();

        assertThat(meterRegistry.find("budgeting.idempotency.cleanup").counters()).isEmpty();
    }

    @Test
    void run_whenBatchThrows_recordsFailureCounter_andNeverPropagates() {
        var cleanupService = mock(IdempotencyCleanupService.class);
        when(cleanupService.recoverAbandonedProcessing()).thenThrow(new RuntimeException("db unavailable"));
        var meterRegistry = new SimpleMeterRegistry();
        var scheduler = new IdempotencyCleanupScheduler(cleanupService, new AiObservability(meterRegistry));

        scheduler.run();

        var failure = meterRegistry.find("budgeting.idempotency.cleanup")
                .tag("action", "run").tag("result", "failure").counter();
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1.0);
    }
}
