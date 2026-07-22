package io.github.viniciusssantos.accountshield.policy;

import java.time.Instant;

public record PolicyActivated(
        String policyKey,
        String version,
        Instant activatedAt) {
}
