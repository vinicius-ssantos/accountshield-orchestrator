package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.IdempotencyGuard;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordEntity;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DatabaseIdempotencyGuard implements IdempotencyGuard {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String RESOURCE_TYPE = "protection_decision";

    private final IdempotencyRecordRepository repository;
    private final Clock clock;

    DatabaseIdempotencyGuard(IdempotencyRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public IdempotencyResult resolve(String idempotencyKey, String fingerprint, Instant now) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Optional<IdempotencyRecordEntity> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return IdempotencyResult.absent();
        }

        IdempotencyRecordEntity record = existing.get();
        if (record.getExpiresAt().isBefore(now)) {
            return IdempotencyResult.absent();
        }

        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw new ConflictingIdempotencyRequestException(idempotencyKey);
        }

        return IdempotencyResult.duplicate(
                record.getResourceId(),
                record.getRequestFingerprint(),
                record.getResponsePayload(),
                record.getCreatedAt(),
                record.getExpiresAt());
    }

    @Override
    public void record(
            String idempotencyKey,
            String fingerprint,
            String resourceType,
            UUID resourceId,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        repository.save(new IdempotencyRecordEntity(
                UUID.randomUUID(),
                idempotencyKey,
                fingerprint,
                resourceType,
                resourceId,
                responsePayload,
                createdAt,
                expiresAt));
    }

    Instant defaultExpiry(Instant createdAt) {
        return createdAt.plus(DEFAULT_TTL);
    }

    String resourceType() {
        return RESOURCE_TYPE;
    }
}
