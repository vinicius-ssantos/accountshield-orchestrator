package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecoveryAuthorizationIssued(
        UUID authorizationId,
        UUID protectionRequestId,
        UUID decisionId,
        String accountReference,
        String directive,
        int riskScore,
        Instant issuedAt,
        Instant expiresAt) {

    public RecoveryAuthorizationIssued {
        Objects.requireNonNull(authorizationId, "authorizationId must not be null");
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        Objects.requireNonNull(directive, "directive must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
