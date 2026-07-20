package io.github.viniciusssantos.accountshield.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatabasePolicyEvaluationServiceTest {

    private static final String POLICY_KEY = "account-protection-default";

    private final PolicyVersionRepository repository = mock(PolicyVersionRepository.class);
    private final DatabasePolicyEvaluationService service = new DatabasePolicyEvaluationService(repository);

    @Test
    void mapsRiskScoreBoundariesToTheThreeInitialOutcomes() {
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(activePolicy(29, 69)));

        assertThat(service.evaluate(POLICY_KEY, 29).outcome()).isEqualTo(ProtectionOutcome.ALLOW);
        assertThat(service.evaluate(POLICY_KEY, 30).outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);
        assertThat(service.evaluate(POLICY_KEY, 69).outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);
        assertThat(service.evaluate(POLICY_KEY, 70).outcome()).isEqualTo(ProtectionOutcome.TEMPORARILY_BLOCK);
    }

    @Test
    void returnsTheResolvedImmutablePolicyIdentity() {
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(activePolicy(29, 69)));

        var evaluation = service.evaluate(POLICY_KEY, 10);

        assertThat(evaluation.policyKey()).isEqualTo(POLICY_KEY);
        assertThat(evaluation.policyVersion()).isEqualTo("1.0.0");
    }

    @Test
    void failsClosedWhenNoActivePolicyExists() {
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(POLICY_KEY, 10))
                .isInstanceOf(ActivePolicyUnavailableException.class);
    }

    @Test
    void failsClosedWhenActivePolicyThresholdsAreIncomplete() {
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(activePolicy(null, null)));

        assertThatThrownBy(() -> service.evaluate(POLICY_KEY, 10))
                .isInstanceOf(ActivePolicyUnavailableException.class);
    }

    @Test
    void rejectsScoresOutsideTheSupportedRange() {
        assertThatThrownBy(() -> service.evaluate(POLICY_KEY, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskScore");
    }

    private PolicyVersionEntity activePolicy(Integer allowMaxScore, Integer stepUpMaxScore) {
        return new PolicyVersionEntity(
                UUID.fromString("cdb2795f-e494-4c45-a89d-596aa8f04906"),
                POLICY_KEY,
                "1.0.0",
                "ACTIVE",
                toShort(allowMaxScore),
                toShort(stepUpMaxScore),
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:00Z"));
    }

    private Short toShort(Integer value) {
        return value == null ? null : value.shortValue();
    }
}
