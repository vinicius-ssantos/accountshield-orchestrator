package io.github.viniciusssantos.accountshield.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshieldsdk.AccountShieldApiException;
import io.github.viniciusssantos.accountshieldsdk.AccountShieldClient;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengePurpose;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengeVerificationRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengeVerificationResponse;
import io.github.viniciusssantos.accountshieldsdk.model.NetworkRiskLevel;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionResponse;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionEventType;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionOutcome;
import io.github.viniciusssantos.accountshieldsdk.model.RecoveryResponse;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #55's "API compatibility is contract-tested" acceptance criterion, proven the only honest
 * way available in this codebase: there is no checked-in static OpenAPI baseline file to compare
 * the SDK's hand-written models against (ADR 0029 diffs the *live* {@code /v3/api-docs} against a
 * CI-artifact baseline, not a repo file) -- so this test runs the real server on a random port and
 * drives it through {@code accountshield-sdk}'s real {@code AccountShieldClient}, asserting every
 * typed field the SDK parses matches what the real server actually returned. This is the one
 * deliberate, one-directional dependency exception ADR 0037 documents: this server-side test
 * depends on the sdk artifact (test scope, see pom.xml), never the other way around.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSqlTestConfiguration.class)
class SdkContractVerificationTest {

    @Value("${local.server.port}")
    private int port;

    private AccountShieldClient client() {
        return AccountShieldClient.builder(URI.create("http://localhost:" + port)).build();
    }

    @Test
    void decideProtectionParsesARealAllowResponse() {
        ProtectionDecisionResponse response = client().decideProtection(
                ProtectionDecisionRequest.builder(syntheticAccount("allow"), ProtectionEventType.LOGIN_ATTEMPT)
                        .networkRiskLevel(NetworkRiskLevel.LOW)
                        .idempotencyKey("contract-allow-" + UUID.randomUUID())
                        .build());

        assertThat(response.outcome()).isEqualTo(ProtectionOutcome.ALLOW);
        assertThat(response.decisionId()).isNotNull();
        assertThat(response.protectionRequestId()).isNotNull();
        assertThat(response.policyKey()).isEqualTo("account-protection-default");
        assertThat(response.riskScore()).isBetween(0, 100);
        assertThat(response.challenge()).isNull();
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void decideProtectionParsesARealStepUpResponseAndChallengeVerificationRoundTrips() {
        ProtectionDecisionResponse response = client().decideProtection(
                ProtectionDecisionRequest.builder(syntheticAccount("step-up"), ProtectionEventType.LOGIN_ATTEMPT)
                        .impossibleTravel(true)
                        .newDevice(true)
                        .networkRiskLevel(NetworkRiskLevel.MEDIUM)
                        .idempotencyKey("contract-step-up-" + UUID.randomUUID())
                        .build());

        assertThat(response.outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);
        assertThat(response.riskScore()).isEqualTo(60);
        assertThat(response.challenge()).isNotNull();
        assertThat(response.challenge().challengeId()).isNotNull();
        assertThat(response.challenge().challengeType()).isNotNull();

        ChallengeVerificationResponse verification = client().verifyChallenge(
                response.challenge().challengeId(),
                new ChallengeVerificationRequest("000000", ChallengePurpose.PROTECTION_STEP_UP, response.protectionRequestId()),
                null);

        assertThat(verification.challengeId()).isEqualTo(response.challenge().challengeId());
        assertThat(verification.verified()).isFalse();
        assertThat(verification.remainingAttempts()).isEqualTo(2);
    }

    @Test
    void decideProtectionParsesARealStartRecoveryResponseAndInitiateRecoveryRoundTrips() {
        ProtectionDecisionResponse response = client().decideProtection(
                ProtectionDecisionRequest.builder(syntheticAccount("recovery"), ProtectionEventType.PASSWORD_RESET_ATTEMPT)
                        .compromisedCredential(true)
                        .impossibleTravel(true)
                        .networkRiskLevel(NetworkRiskLevel.LOW)
                        .idempotencyKey("contract-recovery-" + UUID.randomUUID())
                        .build());

        assertThat(response.outcome()).isEqualTo(ProtectionOutcome.START_RECOVERY);
        assertThat(response.recoveryAuthorizationId()).isNotNull();

        RecoveryResponse recovery = client().initiateRecovery(response.recoveryAuthorizationId(), null);

        assertThat(recovery.recoveryId()).isNotNull();
        assertThat(recovery.authorizationId()).isEqualTo(response.recoveryAuthorizationId());
        assertThat(recovery.protectionRequestId()).isEqualTo(response.protectionRequestId());
        assertThat(recovery.status()).isNotBlank();
        assertThat(recovery.classification()).isNotBlank();
    }

    @Test
    void aValidationErrorParsesIntoTypedProblemDetailsMatchingTheRealResponse() {
        assertThatThrownBy(() -> client().decideProtection(
                ProtectionDecisionRequest.builder("", ProtectionEventType.LOGIN_ATTEMPT).build()))
                .isInstanceOf(AccountShieldApiException.class)
                .satisfies(exception -> {
                    AccountShieldApiException apiException = (AccountShieldApiException) exception;
                    assertThat(apiException.httpStatus()).isEqualTo(400);
                    assertThat(apiException.problem().status()).isEqualTo(400);
                    assertThat(apiException.problem().title()).isNotBlank();
                    assertThat(apiException.problem().type()).startsWith("urn:accountshield:problem:");
                });
    }

    private String syntheticAccount(String slug) {
        return "sdk-contract-" + slug + "-" + UUID.randomUUID() + "@example.test";
    }
}
