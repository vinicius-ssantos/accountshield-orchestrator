package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtectionMetricsRecorderTest {

    @Test
    void recordsCounterAndRiskScorePerOutcome() {
        var registry = new SimpleMeterRegistry();
        var recorder = new ProtectionMetricsRecorder(registry);

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-1",
                "ALLOW", 5, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:00Z"), false, null, "default-client", null, null));

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-2",
                "TEMPORARILY_BLOCK", 85, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:01Z"), false, null, "default-client", null, null));

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-3",
                "ALLOW", 10, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:02Z"), false, null, "default-client", null, null));

        var allowCounter = registry.find("accountshield.protection.decisions")
                .tag("outcome", "ALLOW")
                .counter();
        assertThat(allowCounter).isNotNull();
        assertThat(allowCounter.count()).isEqualTo(2.0);

        var blockCounter = registry.find("accountshield.protection.decisions")
                .tag("outcome", "TEMPORARILY_BLOCK")
                .counter();
        assertThat(blockCounter).isNotNull();
        assertThat(blockCounter.count()).isEqualTo(1.0);

        var allowScores = registry.find("accountshield.protection.risk_score")
                .tag("outcome", "ALLOW")
                .summary();
        assertThat(allowScores).isNotNull();
        assertThat(allowScores.count()).isEqualTo(2);
        assertThat(allowScores.mean()).isBetween(4.0, 11.0);
    }

    @Test
    void separatesCountersByPolicyKey() {
        var registry = new SimpleMeterRegistry();
        var recorder = new ProtectionMetricsRecorder(registry);

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-1",
                "ALLOW", 0, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:00Z"), false, null, "default-client", null, null));

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-2",
                "ALLOW", 0, "strict-summer-policy", "2.0.0",
                Instant.parse("2026-07-22T12:00:01Z"), false, null, "default-client", null, null));

        var defaultCounter = registry.find("accountshield.protection.decisions")
                .tag("policy_key", "account-protection-default")
                .counter();
        assertThat(defaultCounter).isNotNull();
        assertThat(defaultCounter.count()).isEqualTo(1.0);

        var strictCounter = registry.find("accountshield.protection.decisions")
                .tag("policy_key", "strict-summer-policy")
                .counter();
        assertThat(strictCounter).isNotNull();
        assertThat(strictCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordsDegradedDecisionCounterTaggedByReason() {
        var registry = new SimpleMeterRegistry();
        var recorder = new ProtectionMetricsRecorder(registry);

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-1",
                "TEMPORARILY_BLOCK", 0, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:00Z"), true, "CHALLENGE_PROVIDER_UNAVAILABLE", "default-client", null, null));

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-2",
                "ALLOW", 0, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:01Z"), false, null, "default-client", null, null));

        var degradedCounter = registry.find("accountshield.protection.degraded_decisions")
                .tag("reason", "CHALLENGE_PROVIDER_UNAVAILABLE")
                .counter();
        assertThat(degradedCounter).isNotNull();
        assertThat(degradedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordsRolloutDecisionCounterTaggedBySelectionOnlyWhenARolloutWasActive() {
        var registry = new SimpleMeterRegistry();
        var recorder = new ProtectionMetricsRecorder(registry);

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-1",
                "ALLOW", 0, "account-protection-default", "2.0.0",
                Instant.parse("2026-07-22T12:00:00Z"), false, null, "default-client", "2.0.0", true));

        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-2",
                "ALLOW", 0, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:01Z"), false, null, "default-client", "2.0.0", false));

        // no active rollout for this decision -- must not appear in the rollout counter at all
        recorder.onDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-3",
                "ALLOW", 0, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:02Z"), false, null, "default-client", null, null));

        var candidateCounter = registry.find("accountshield.policy.rollout.decisions")
                .tag("selection", "candidate")
                .counter();
        assertThat(candidateCounter).isNotNull();
        assertThat(candidateCounter.count()).isEqualTo(1.0);

        var stableCounter = registry.find("accountshield.policy.rollout.decisions")
                .tag("selection", "stable")
                .counter();
        assertThat(stableCounter).isNotNull();
        assertThat(stableCounter.count()).isEqualTo(1.0);

        assertThat(registry.find("accountshield.policy.rollout.decisions").counters()).hasSize(2);
    }
}
