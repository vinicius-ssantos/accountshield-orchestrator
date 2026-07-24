package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.protection.RecoveryAuthorizationIssued;
import io.github.viniciusssantos.accountshield.recovery.RecoveryAuthorization;
import io.github.viniciusssantos.accountshield.recovery.RecoveryDirective;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryAuthorizationEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryAuthorizationRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RecoveryAuthorizationApplicationService {

    private final RecoveryAuthorizationRepository repository;

    RecoveryAuthorizationApplicationService(RecoveryAuthorizationRepository repository) {
        this.repository = repository;
    }

    @EventListener
    @Transactional
    public void issue(RecoveryAuthorizationIssued event) {
        Objects.requireNonNull(event, "event must not be null");

        RecoveryAuthorization authorization = new RecoveryAuthorization(
                event.authorizationId(),
                event.protectionRequestId(),
                event.decisionId(),
                event.accountReference(),
                RecoveryDirective.valueOf(event.directive()),
                event.riskScore(),
                event.issuedAt(),
                event.expiresAt(),
                null);

        repository.findByDecisionId(event.decisionId()).ifPresentOrElse(existing -> {
            if (!existing.getId().equals(event.authorizationId())) {
                throw new IllegalStateException(
                        "decision already has a different recovery authorization");
            }
        }, () -> repository.save(toEntity(authorization)));
    }

    @Transactional
    public Optional<Consumption> consume(UUID authorizationId, Instant now) {
        Objects.requireNonNull(authorizationId, "authorizationId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        return repository.findByIdForUpdate(authorizationId).flatMap(entity -> {
            RecoveryAuthorization authorization = toDomain(entity);
            if (authorization.consumed()) {
                return Optional.of(new Consumption(authorization, false));
            }
            if (authorization.expiredAt(now)) {
                return Optional.empty();
            }

            entity.consume(now);
            return Optional.of(new Consumption(toDomain(entity), true));
        });
    }

    private RecoveryAuthorizationEntity toEntity(RecoveryAuthorization authorization) {
        return new RecoveryAuthorizationEntity(
                authorization.authorizationId(),
                authorization.protectionRequestId(),
                authorization.decisionId(),
                authorization.accountReference(),
                authorization.directive().name(),
                authorization.riskScore(),
                authorization.issuedAt(),
                authorization.expiresAt(),
                authorization.consumedAt());
    }

    private RecoveryAuthorization toDomain(RecoveryAuthorizationEntity entity) {
        return new RecoveryAuthorization(
                entity.getId(),
                entity.getProtectionRequestId(),
                entity.getDecisionId(),
                entity.getAccountReference(),
                RecoveryDirective.valueOf(entity.getDirective()),
                entity.getRiskScore(),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getConsumedAt());
    }

    record Consumption(RecoveryAuthorization authorization, boolean newlyConsumed) {
    }
}
