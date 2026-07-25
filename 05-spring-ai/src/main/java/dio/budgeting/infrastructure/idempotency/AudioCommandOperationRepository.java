package dio.budgeting.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AudioCommandOperationRepository extends JpaRepository<AudioCommandOperationEntity, UUID> {
    Optional<AudioCommandOperationEntity> findByIdempotencyKey(String idempotencyKey);
}
