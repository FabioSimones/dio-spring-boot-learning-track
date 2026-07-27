package dio.budgeting.infrastructure.idempotency;

import dio.budgeting.infrastructure.observability.AiObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic recovery+cleanup for {@code audio_command_operation} (TASK-010).
 * Recovery always runs before cleanup in the same tick: a row just marked
 * FAILED by recovery has a fresh {@code updatedAt}, so it is never also
 * eligible for deletion in that same pass.
 *
 * <p>Disabled entirely via {@code app.idempotency.cleanup-enabled=false} (the
 * test profile default - see {@code src/test/resources/application-test.properties}),
 * so the test suite never runs this on a real clock; {@code
 * IdempotencyCleanupSchedulerTest} calls {@link #run()} directly instead.
 *
 * <p>Never calls OpenAI, never reprocesses audio, never touches transactions -
 * only status/timestamps on this one table, and only aggregate counts are
 * logged (never the key, fingerprint, or response text).
 */
@Component
@ConditionalOnProperty(prefix = "app.idempotency", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final IdempotencyCleanupService cleanupService;
    private final AiObservability observability;

    public IdempotencyCleanupScheduler(IdempotencyCleanupService cleanupService, AiObservability observability) {
        this.cleanupService = cleanupService;
        this.observability = observability;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval}")
    public void run() {
        try {
            int recovered = cleanupService.recoverAbandonedProcessing();
            int removed = cleanupService.cleanupExpired();
            observability.recordCleanup("recovered", recovered);
            observability.recordCleanup("deleted", removed);
            log.info("Idempotência: operações abandonadas recuperadas={}, operações expiradas removidas={}", recovered, removed);
        }
        catch (RuntimeException ex) {
            // Per-row conflicts are already handled inside IdempotencyCleanupService
            // (an OptimisticLockingFailureException there only skips that one row) -
            // this only guards against a truly unexpected failure of the whole batch
            // (e.g. a DB connectivity issue), so one bad tick never derails the next.
            observability.recordCleanupFailure();
            log.error("Falha inesperada na limpeza de idempotência.", ex);
        }
    }
}
