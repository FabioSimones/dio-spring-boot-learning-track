package dio.budgeting.infrastructure.idempotency;

import dio.budgeting.infrastructure.ai.AiIntegrationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks one POST /transactions/ai invocation identified by an
 * {@code Idempotency-Key}. Never stores the audio itself (input or
 * generated) - only {@code responseText} (the safe final ChatClient reply),
 * so a replay of a COMPLETED operation can regenerate audio via TTS alone,
 * without repeating transcription/chat/Tool Calling.
 */
@Entity
@Table(name = "audio_command_operation",
        uniqueConstraints = @UniqueConstraint(name = "uk_audio_command_operation_key", columnNames = "idempotency_key"))
@Getter
@Setter
@NoArgsConstructor
public class AudioCommandOperationEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "payload_fingerprint", nullable = false, length = 64)
    private String payloadFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AudioCommandStatus status;

    /**
     * Stage where a FAILED operation stopped. Null unless status is FAILED.
     * Drives the retry policy in AudioCommandIdempotencyService: TRANSCRIPTION
     * failures may retry (no side effect could have happened yet); CHAT/SPEECH
     * failures block reuse (Tool Calling may already have persisted).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 20)
    private AiIntegrationException.Stage failureStage;

    /**
     * Always null in this task: the transaction ID created by Tool Calling
     * cannot be captured safely without either duplicating the @Tool
     * annotation on a wrapper or parsing the AI's text response (both
     * rejected). Kept as a nullable column for a future task that solves this
     * properly.
     */
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "response_text", length = 2000)
    private String responseText;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;

    public AudioCommandOperationEntity(String idempotencyKey, String payloadFingerprint) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.payloadFingerprint = payloadFingerprint;
        this.status = AudioCommandStatus.PROCESSING;
        var now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
