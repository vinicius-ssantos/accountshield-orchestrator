package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.IdempotencyGuard;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordEntity;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DatabaseIdempotencyGuard implements IdempotencyGuard {

    private static final String RESOURCE_TYPE = "protection_decision";
    private static final String IDEMPOTENCY_METRIC = "accountshield.protection.idempotency";

    /**
     * A conflicting insert that resolves quickly reflects a row that was already fully settled
     * before we even asked; one that takes noticeably longer was blocked by Postgres behind a
     * concurrent, still-in-flight transaction holding the same key. This is a best-effort
     * heuristic for the HIT/RACE metric split only — it never affects correctness.
     */
    private static final long RACE_HEURISTIC_THRESHOLD_MILLIS = 25;

    private final IdempotencyRecordRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private final MeterRegistry meterRegistry;

    DatabaseIdempotencyGuard(
            IdempotencyRecordRepository repository,
            Clock clock,
            @Value("${accountshield.protection.idempotency.ttl:24h}") Duration ttl,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public IdempotencyResult claim(
            String clientId, String idempotencyKey, String fingerprint, UUID resourceId, Instant now) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        ClaimAttempt attempt = tryClaim(clientId, idempotencyKey, fingerprint, resourceId, now);
        if (attempt.won()) {
            increment("MISS");
            return IdempotencyResult.absent();
        }

        // The insert reported a conflict: a row already exists and is fully committed (Postgres
        // blocks a conflicting insert until any in-flight transaction holding the row resolves).
        IdempotencyRecordEntity existing = repository.findByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency claim conflicted but no row could be re-read for key: " + idempotencyKey));

        if (existing.getExpiresAt().isBefore(now)) {
            repository.deleteByClientIdAndIdempotencyKey(clientId, idempotencyKey);
            increment("EXPIRED");
            ClaimAttempt retried = tryClaim(clientId, idempotencyKey, fingerprint, resourceId, now);
            if (retried.won()) {
                increment("MISS");
                return IdempotencyResult.absent();
            }
            existing = repository.findByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency claim conflicted after expiry cleanup for key: " + idempotencyKey));
        }

        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            increment("CONFLICT");
            throw new ConflictingIdempotencyRequestException(idempotencyKey);
        }

        if (existing.getResponsePayload() == null) {
            // A previous attempt reserved this key and never finalized it (a process crash
            // between claim and finalizeResult — see ADR 0018's accepted-risk note). It never
            // legitimately reflects a request still executing right now: a truly concurrent,
            // still-in-flight claim would have blocked our insert rather than let it return.
            // Fail closed rather than either silently redoing the work or fabricating a payload.
            increment("CONFLICT");
            throw new ConflictingIdempotencyRequestException(idempotencyKey);
        }

        increment(attempt.likelyBlocked() ? "RACE" : "HIT");
        return IdempotencyResult.duplicate(
                existing.getResourceId(),
                existing.getRequestFingerprint(),
                existing.getResponsePayload(),
                existing.getCreatedAt(),
                existing.getExpiresAt());
    }

    @Override
    @Transactional
    public void finalizeResult(String clientId, String idempotencyKey, String responsePayload) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(responsePayload, "responsePayload must not be null");
        repository.updateResponsePayload(clientId, idempotencyKey, responsePayload);
    }

    private ClaimAttempt tryClaim(
            String clientId, String idempotencyKey, String fingerprint, UUID resourceId, Instant now) {
        long startNanos = System.nanoTime();
        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(), clientId, idempotencyKey, fingerprint, RESOURCE_TYPE, resourceId,
                now, now.plus(ttl));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new ClaimAttempt(inserted > 0, elapsedMillis >= RACE_HEURISTIC_THRESHOLD_MILLIS);
    }

    private void increment(String outcome) {
        Counter.builder(IDEMPOTENCY_METRIC)
                .description("Total idempotency claim outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private record ClaimAttempt(boolean won, boolean likelyBlocked) {
    }
}
