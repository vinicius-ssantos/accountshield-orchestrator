package io.github.viniciusssantos.accountshield.policy;

public enum ProtectionOutcome {
    ALLOW,
    REQUIRE_STEP_UP,
    START_RECOVERY,
    TEMPORARILY_BLOCK
}
