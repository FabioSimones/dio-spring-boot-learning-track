package dio.budgeting.infrastructure.idempotency;

import dio.budgeting.infrastructure.ai.AiIntegrationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Decides, for a given {@code Idempotency-Key} + payload fingerprint,
 * whether POST /transactions/ai must run the full AI pipeline or can replay
 * an already-completed result. See {@link IdempotencyDecision}.
 *
 * <p>Concurrency: two simultaneous requests with the same brand-new key can
 * both race past the initial {@code findByKey} check. The unique constraint
 * on {@code idempotency_key} (enforced by the database, not by this
 * in-memory check) makes one of the two {@code insertNew} calls fail with
 * {@link DataIntegrityViolationException}; that request re-reads the row the
 * winner just created and re-evaluates it as an existing operation (almost
 * certainly still PROCESSING, so it reports {@code IN_PROGRESS}).
 */
@Service
public class AudioCommandIdempotencyService {

    private final AudioCommandOperationStore store;

    public AudioCommandIdempotencyService(AudioCommandOperationStore store) {
        this.store = store;
    }

    public IdempotencyDecision begin(String idempotencyKey, String payloadFingerprint) {
        IdempotencyKeyValidator.validate(idempotencyKey);
        return beginAfterValidation(idempotencyKey, payloadFingerprint, true);
    }

    private IdempotencyDecision beginAfterValidation(String idempotencyKey, String payloadFingerprint,
                                                       boolean mayAttemptInsert) {
        var existing = store.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), payloadFingerprint);
        }
        if (!mayAttemptInsert) {
            // Lost the race, and the winner's row is still not visible to us
            // (e.g. its own transaction hasn't committed yet). Safe default:
            // treat it the same as an in-progress operation rather than retry.
            throw new IdempotencyException(IdempotencyException.Reason.IN_PROGRESS,
                    "Uma operação com esta chave já está em processamento.");
        }
        try {
            var created = store.insertNew(idempotencyKey, payloadFingerprint);
            return new IdempotencyDecision.Start(created.getId());
        }
        catch (DataIntegrityViolationException raceLost) {
            return beginAfterValidation(idempotencyKey, payloadFingerprint, false);
        }
    }

    private IdempotencyDecision resolveExisting(AudioCommandOperationEntity entity, String payloadFingerprint) {
        if (!entity.getPayloadFingerprint().equals(payloadFingerprint)) {
            throw new IdempotencyException(IdempotencyException.Reason.PAYLOAD_CONFLICT,
                    "A chave de idempotência já foi utilizada com outro arquivo.");
        }
        return switch (entity.getStatus()) {
            case PROCESSING -> throw new IdempotencyException(IdempotencyException.Reason.IN_PROGRESS,
                    "Uma operação com esta chave já está em processamento.");
            case COMPLETED -> new IdempotencyDecision.Replay(entity.getResponseText(), entity.getTransactionId());
            case FAILED -> resolveFailed(entity);
        };
    }

    private IdempotencyDecision resolveFailed(AudioCommandOperationEntity entity) {
        if (entity.getFailureStage() != AiIntegrationException.Stage.TRANSCRIPTION) {
            // CHAT or SPEECH failure: Tool Calling may already have persisted a
            // transaction before the failure - never safe to reprocess automatically.
            throw new IdempotencyException(IdempotencyException.Reason.RETRY_NOT_ALLOWED,
                    "Esta chave de idempotência falhou em uma etapa que pode já ter persistido uma "
                            + "transação. Utilize uma nova chave para tentar novamente.");
        }
        try {
            var restarted = store.restartForRetry(entity.getId());
            return new IdempotencyDecision.Start(restarted.getId());
        }
        catch (OptimisticLockingFailureException concurrentRetry) {
            throw new IdempotencyException(IdempotencyException.Reason.IN_PROGRESS,
                    "Uma operação com esta chave já está em processamento.");
        }
    }

    public void complete(UUID operationId, String responseText, UUID transactionId) {
        store.complete(operationId, responseText, transactionId);
    }

    public void fail(UUID operationId, AiIntegrationException.Stage stage) {
        store.fail(operationId, stage);
    }
}
