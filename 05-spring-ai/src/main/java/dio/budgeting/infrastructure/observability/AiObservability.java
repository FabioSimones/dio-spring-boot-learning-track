package dio.budgeting.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Single point of contact with Micrometer (TASK-011): every counter/timer in
 * this module goes through here, so no other class touches {@link
 * MeterRegistry} directly - callers only ever call semantic methods
 * ("a stage started", "a replay happened"), never raw metric names.
 *
 * <p>Tags are deliberately restricted to a small, closed vocabulary (stage
 * name, success/failure, replayed true/false, the controlled {@code Reason}
 * enums already used for HTTP mapping) - never a correlation ID, an
 * idempotency key, a fingerprint, a filename, or any other high-cardinality
 * or sensitive value. See the README "Observabilidade" section for the full
 * tag contract.
 *
 * <p>Purely additive: nothing here can influence the business outcome of a
 * request - every method either records a measurement or logs, and returns
 * either {@code void} or an opaque {@link Timer.Sample} to be closed later.
 */
@Component
public class AiObservability {

    private static final Logger log = LoggerFactory.getLogger(AiObservability.class);

    private final MeterRegistry registry;

    public AiObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    // --- Total request (transcription+chat+speech, or replay-only TTS) ---

    public Timer.Sample startTotal() {
        log.info("Operação de IA iniciada. event=AI_REQUEST_STARTED");
        return Timer.start(registry);
    }

    public void completeTotal(Timer.Sample sample, boolean success, boolean replayed) {
        sample.stop(Timer.builder("budgeting.ai.requests")
                .tag("result", success ? "success" : "failure")
                .tag("replayed", String.valueOf(replayed))
                .register(registry));
        log.info("Operação de IA finalizada. event={} replayed={}",
                success ? "AI_REQUEST_COMPLETED" : "AI_REQUEST_FAILED", replayed);
    }

    // --- Per-stage (transcription / chat / speech) ---

    public Timer.Sample startStage(String stage) {
        log.debug("Etapa de IA iniciada. event=AI_{}_STARTED stage={}", stage.toUpperCase(Locale.ROOT), stage);
        return Timer.start(registry);
    }

    public void completeStageSuccess(Timer.Sample sample, String stage) {
        sample.stop(Timer.builder("budgeting.ai.stage.duration")
                .tag("stage", stage)
                .tag("result", "success")
                .register(registry));
        log.info("Etapa de IA concluída. event=AI_{}_COMPLETED stage={}", stage.toUpperCase(Locale.ROOT), stage);
    }

    /**
     * Records both the stage timer (result=failure) and the failure counter in
     * one call - a stage failure is always both. The exception itself (with
     * stack trace) is logged exactly once elsewhere, in {@code
     * AiTransactionProcessor}; this only logs the classified event/reason, no
     * exception object, no message.
     */
    public void completeStageFailure(Timer.Sample sample, String stage, String reason) {
        sample.stop(Timer.builder("budgeting.ai.stage.duration")
                .tag("stage", stage)
                .tag("result", "failure")
                .register(registry));
        registry.counter("budgeting.ai.failures", "stage", stage, "reason", reason).increment();
        log.debug("Etapa de IA falhou. event=AI_{}_FAILED stage={} reason={}", stage.toUpperCase(Locale.ROOT), stage, reason);
    }

    // --- Upload validation (before any OpenAI call) ---

    public void recordUploadRejection(String reason) {
        registry.counter("budgeting.ai.upload.rejections", "reason", reason).increment();
        log.info("Upload de áudio rejeitado. event=AI_UPLOAD_REJECTED reason={}", reason);
    }

    // --- Idempotency ---

    public void recordIdempotencyStart() {
        registry.counter("budgeting.ai.idempotency.operations", "outcome", "new").increment();
    }

    public void recordIdempotencyReplay() {
        registry.counter("budgeting.ai.idempotency.replays").increment();
        log.info("Replay idempotente. event=AI_IDEMPOTENCY_REPLAY");
    }

    /**
     * @param reason the controlled {@code IdempotencyException.Reason} name,
     *               lower-cased (payload_conflict, in_progress, retry_not_allowed) -
     *               never the idempotency key or fingerprint.
     */
    public void recordIdempotencyConflict(String reason) {
        registry.counter("budgeting.ai.idempotency.conflicts", "reason", reason).increment();
        log.warn("Conflito de idempotência. event=AI_IDEMPOTENCY_CONFLICT reason={}", reason);
    }

    // --- Idempotency cleanup scheduler ---

    public void recordCleanup(String action, int count) {
        if (count <= 0) {
            return;
        }
        registry.counter("budgeting.idempotency.cleanup", "action", action, "result", "success").increment(count);
    }

    public void recordCleanupFailure() {
        registry.counter("budgeting.idempotency.cleanup", "action", "run", "result", "failure").increment();
    }
}
