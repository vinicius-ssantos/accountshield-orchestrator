package io.github.viniciusssantos.accountshield.policy;

import java.time.Instant;

public record PolicyGovernance(
        String createdBy,
        String validatedBy,
        Instant validatedAt,
        String approvedBy,
        Instant approvedAt,
        String approvalReason) {
}
