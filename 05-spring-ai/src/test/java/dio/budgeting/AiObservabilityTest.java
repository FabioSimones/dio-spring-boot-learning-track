package dio.budgeting;

import dio.budgeting.infrastructure.observability.AiObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AiObservability (TASK-011): no Spring context, a plain
 * SimpleMeterRegistry. Focuses on the tag contract itself - every counter/timer
 * carries only the controlled vocabulary (stage, result, reason, replayed,
 * outcome, action), never a correlation ID, key, fingerprint or free-text
 * value - and that timers/counters are registered under the documented names.
 */
class AiObservabilityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AiObservability observability = new AiObservability(registry);

    @Test
    void totalRequest_success_recordsExpectedNameAndTags() {
        var sample = observability.startTotal();
        observability.completeTotal(sample, true, false);

        var timer = registry.find("budgeting.ai.requests").tag("result", "success").tag("replayed", "false").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).hasSize(2);
    }

    @Test
    void totalRequest_failure_replayed_recordsExpectedTags() {
        var sample = observability.startTotal();
        observability.completeTotal(sample, false, true);

        var timer = registry.find("budgeting.ai.requests").tag("result", "failure").tag("replayed", "true").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void stageSuccess_recordsStageDurationTimer_noFailureCounter() {
        var sample = observability.startStage("transcription");
        observability.completeStageSuccess(sample, "transcription");

        var timer = registry.find("budgeting.ai.stage.duration")
                .tag("stage", "transcription").tag("result", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(registry.find("budgeting.ai.failures").counters()).isEmpty();
    }

    @Test
    void stageFailure_recordsBothTimerAndFailureCounter_withOnlyStageAndReasonTags() {
        var sample = observability.startStage("chat");
        observability.completeStageFailure(sample, "chat", "provider_unavailable");

        var timer = registry.find("budgeting.ai.stage.duration").tag("stage", "chat").tag("result", "failure").timer();
        var counter = registry.find("budgeting.ai.failures")
                .tag("stage", "chat").tag("reason", "provider_unavailable").counter();
        assertThat(timer).isNotNull();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTags()).hasSize(2);
    }

    @Test
    void uploadRejection_recordsOnlyReasonTag() {
        observability.recordUploadRejection("unsupported_type");

        var counter = registry.find("budgeting.ai.upload.rejections").tag("reason", "unsupported_type").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).hasSize(1);
    }

    @Test
    void idempotencyStart_recordsOperationsCounter_withOutcomeNew() {
        observability.recordIdempotencyStart();

        var counter = registry.find("budgeting.ai.idempotency.operations").tag("outcome", "new").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void idempotencyReplay_recordsDedicatedCounter_noTags() {
        observability.recordIdempotencyReplay();

        var counter = registry.find("budgeting.ai.idempotency.replays").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTags()).isEmpty();
    }

    @Test
    void idempotencyConflict_recordsOnlyReasonTag_neverKeyOrFingerprint() {
        observability.recordIdempotencyConflict("in_progress");

        var counter = registry.find("budgeting.ai.idempotency.conflicts").tag("reason", "in_progress").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).hasSize(1);
        assertThat(counter.getId().getTags().getFirst().getKey()).isEqualTo("reason");
    }

    @Test
    void cleanup_recordsActionAndResultTags_incrementedByCount() {
        observability.recordCleanup("recovered", 4);

        var counter = registry.find("budgeting.idempotency.cleanup")
                .tag("action", "recovered").tag("result", "success").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(4.0);
    }

    @Test
    void cleanup_withZeroCount_doesNotCreateMeter() {
        observability.recordCleanup("deleted", 0);

        assertThat(registry.find("budgeting.idempotency.cleanup").counters()).isEmpty();
    }

    @Test
    void cleanupFailure_recordsFailureCounter() {
        observability.recordCleanupFailure();

        var counter = registry.find("budgeting.idempotency.cleanup")
                .tag("action", "run").tag("result", "failure").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
