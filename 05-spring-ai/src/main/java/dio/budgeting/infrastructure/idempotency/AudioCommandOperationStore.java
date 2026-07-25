package dio.budgeting.infrastructure.idempotency;

import dio.budgeting.infrastructure.ai.AiIntegrationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every write to {@code audio_command_operation} in its own short
 * transaction. Kept as a separate bean (not methods on {@code
 * AudioCommandIdempotencyService}) so each {@code @Transactional} call goes
 * through Spring's proxy - a self-invocation from within the same class would
 * silently skip the proxy and the transaction. This keeps the (potentially
 * 60s+) OpenAI calls in AiTransactionProcessor fully outside any JPA
 * transaction: each of insertNew/complete/fail/restartForRetry is its own
 * commit, released immediately.
 */
@Component
public class AudioCommandOperationStore {

    private final AudioCommandOperationRepository repository;

    AudioCommandOperationStore(AudioCommandOperationRepository repository) {
        this.repository = repository;
    }

    Optional<AudioCommandOperationEntity> findByKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional
    AudioCommandOperationEntity insertNew(String idempotencyKey, String payloadFingerprint) {
        return repository.save(new AudioCommandOperationEntity(idempotencyKey, payloadFingerprint));
    }

    @Transactional
    AudioCommandOperationEntity restartForRetry(UUID operationId) {
        var entity = repository.findById(operationId).orElseThrow();
        entity.setStatus(AudioCommandStatus.PROCESSING);
        entity.setFailureStage(null);
        entity.setUpdatedAt(OffsetDateTime.now());
        return repository.save(entity);
    }

    @Transactional
    void complete(UUID operationId, String responseText, UUID transactionId) {
        var entity = repository.findById(operationId).orElseThrow();
        entity.setStatus(AudioCommandStatus.COMPLETED);
        entity.setResponseText(responseText);
        entity.setTransactionId(transactionId);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
    }

    @Transactional
    void fail(UUID operationId, AiIntegrationException.Stage stage) {
        var entity = repository.findById(operationId).orElseThrow();
        entity.setStatus(AudioCommandStatus.FAILED);
        entity.setFailureStage(stage);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
    }
}
