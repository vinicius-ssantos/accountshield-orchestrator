package io.github.viniciusssantos.accountshield.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A plain string field in an integration-event payload or HTTP response DTO (e.g. {@code outcome},
 * {@code finalStatus}, {@code classification}) does not self-document its allowed value set the
 * way an OpenAPI schema's {@code enum} keyword does ({@link OpenApiSchemaCompatibilityChecker}
 * already catches enum-value removal there) -- so for the domain enums actually serialized into
 * public API responses or integration events, this test hardcodes the constant set that must never
 * silently shrink. Adding a new constant is always safe (additive) and does not need this test
 * updated; removing or renaming one is a breaking change to every consumer keyed off that string
 * value and must fail here until an operator deliberately updates the expectation as part of an
 * intentional major-version change (ADR 0029).
 */
class DomainEnumCompatibilityTest {

    @Test
    void challengeTypeRetainsEveryPublishedConstant() {
        assertStillPresent(ChallengeType.class, "TOTP_SIMULATED", "EMAIL_SIMULATED", "WEBAUTHN_SIMULATED");
    }

    @Test
    void challengeStatusRetainsEveryPublishedConstant() {
        assertStillPresent(ChallengeStatus.class,
                "PENDING", "CHALLENGED", "VERIFIED", "CONSUMED", "FAILED", "EXPIRED");
    }

    @Test
    void protectionOutcomeRetainsEveryPublishedConstant() {
        assertStillPresent(ProtectionOutcome.class,
                "ALLOW", "REQUIRE_STEP_UP", "START_RECOVERY", "TEMPORARILY_BLOCK");
    }

    @Test
    void recoveryEventTypeRetainsEveryPublishedConstant() {
        assertStillPresent(RecoveryEventType.class,
                "LOGIN", "PASSWORD_RESET", "CREDENTIAL_CHANGE", "DEVICE_TRUST_RESET");
    }

    @Test
    void recoveryRiskClassificationRetainsEveryPublishedConstant() {
        assertStillPresent(RecoveryRiskClassification.class, "IMMEDIATE", "DELAYED", "MANUAL_REVIEW");
    }

    @Test
    void riskBandRetainsEveryPublishedConstant() {
        assertStillPresent(RiskBand.class, "LOW", "MEDIUM", "HIGH");
    }

    private <E extends Enum<E>> void assertStillPresent(Class<E> enumType, String... expectedConstants) {
        List<String> actual = Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
        assertThat(actual).containsAll(List.of(expectedConstants));
    }
}
