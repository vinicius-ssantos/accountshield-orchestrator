package io.github.viniciusssantos.accountshielddemo;

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
import io.github.viniciusssantos.accountshieldsdk.webhook.WebhookSignatureVerifier;
import io.github.viniciusssantos.accountshieldsdk.webhook.WebhookSigner;
import io.github.viniciusssantos.accountshieldsdk.webhook.WebhookVerificationResult;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Issue #55's "realistic Java consumer" demo, built entirely on {@code accountshield-sdk} -- no
 * dependency on any server-internal package. Submits three protection decisions covering all three
 * consumer-visible outcomes (ALLOW, REQUIRE_STEP_UP, START_RECOVERY -- using the exact signal
 * combinations ADR 0034's scenario lab already hand-verified against the live scoring formula),
 * handles the step-up and recovery branches, then demonstrates webhook signature verification and
 * replay protection with a self-constructed sample payload (see ADR 0037 for why this demo signs
 * its own sample webhook rather than wiring a live, authenticated subscription -- webhook
 * subscription management is an operator-authenticated surface, issue #19/#48 scope, not yet
 * appropriate for an unauthenticated demo consumer). Prints a simple event timeline throughout.
 *
 * <p>Run against a live AccountShield instance (default {@code http://localhost:8080}, override
 * with the {@code ACCOUNTSHIELD_BASE_URL} environment variable): {@code
 * java -jar accountshield-demo.jar}.
 */
public final class AccountShieldDemo {

    private static final String DEMO_WEBHOOK_SECRET = "accountshield-local-only-demo-receiver-secret";

    public static void main(String[] args) {
        EventTimeline timeline = new EventTimeline();
        String baseUrl = System.getenv().getOrDefault("ACCOUNTSHIELD_BASE_URL", "http://localhost:8080");
        AccountShieldClient client = AccountShieldClient.builder(URI.create(baseUrl)).build();

        try {
            timeline.record("Connecting to AccountShield at " + baseUrl);
            runAllowScenario(client, timeline);
            runStepUpScenario(client, timeline);
            runRecoveryScenario(client, timeline);
            runWebhookVerificationDemo(timeline);
            timeline.printSummary();
            System.out.println();
            System.out.println("Demo completed successfully.");
        } catch (RuntimeException exception) {
            timeline.record("FAILED: " + exception);
            timeline.printSummary();
            exception.printStackTrace();
            System.exit(1);
        }
    }

    private static void runAllowScenario(AccountShieldClient client, EventTimeline timeline) {
        timeline.record("Submitting a low-risk login attempt (expect ALLOW)");
        ProtectionDecisionRequest request = ProtectionDecisionRequest
                .builder(syntheticAccountReference("allow"), ProtectionEventType.LOGIN_ATTEMPT)
                .networkRiskLevel(NetworkRiskLevel.LOW)
                .idempotencyKey("demo-allow-" + UUID.randomUUID())
                .build();
        ProtectionDecisionResponse response = client.decideProtection(request);
        timeline.record("Decision " + response.decisionId() + " -> " + response.outcome()
                + " (score=" + response.riskScore() + ")");
        if (response.outcome() != ProtectionOutcome.ALLOW) {
            throw new IllegalStateException("expected ALLOW, got " + response.outcome());
        }
    }

    private static void runStepUpScenario(AccountShieldClient client, EventTimeline timeline) {
        timeline.record("Submitting a login attempt with impossible travel + a new device (expect REQUIRE_STEP_UP)");
        ProtectionDecisionRequest request = ProtectionDecisionRequest
                .builder(syntheticAccountReference("step-up"), ProtectionEventType.LOGIN_ATTEMPT)
                .impossibleTravel(true)
                .newDevice(true)
                .networkRiskLevel(NetworkRiskLevel.MEDIUM)
                .idempotencyKey("demo-step-up-" + UUID.randomUUID())
                .build();
        ProtectionDecisionResponse response = client.decideProtection(request);
        timeline.record("Decision " + response.decisionId() + " -> " + response.outcome()
                + " (score=" + response.riskScore() + ")");
        if (response.outcome() != ProtectionOutcome.REQUIRE_STEP_UP || response.challenge() == null) {
            throw new IllegalStateException("expected REQUIRE_STEP_UP with a challenge, got " + response.outcome());
        }
        UUID challengeId = response.challenge().challengeId();
        timeline.record("Challenge " + challengeId + " (" + response.challenge().challengeType()
                + ") issued -- submitting a deliberately wrong code to demonstrate handling a challenge response");
        ChallengeVerificationResponse verification = client.verifyChallenge(
                challengeId,
                new ChallengeVerificationRequest("000000", ChallengePurpose.PROTECTION_STEP_UP, response.protectionRequestId()),
                null);
        timeline.record("Challenge " + challengeId + " -> status=" + verification.status()
                + " verified=" + verification.verified()
                + " remainingAttempts=" + verification.remainingAttempts());
    }

    private static void runRecoveryScenario(AccountShieldClient client, EventTimeline timeline) {
        timeline.record("Submitting a password-reset attempt with a compromised credential (expect START_RECOVERY)");
        ProtectionDecisionRequest request = ProtectionDecisionRequest
                .builder(syntheticAccountReference("recovery"), ProtectionEventType.PASSWORD_RESET_ATTEMPT)
                .compromisedCredential(true)
                .impossibleTravel(true)
                .networkRiskLevel(NetworkRiskLevel.LOW)
                .idempotencyKey("demo-recovery-" + UUID.randomUUID())
                .build();
        ProtectionDecisionResponse response = client.decideProtection(request);
        timeline.record("Decision " + response.decisionId() + " -> " + response.outcome()
                + " (score=" + response.riskScore() + ")");
        if (response.outcome() != ProtectionOutcome.START_RECOVERY || response.recoveryAuthorizationId() == null) {
            throw new IllegalStateException("expected START_RECOVERY with an authorization, got " + response.outcome());
        }
        UUID authorizationId = response.recoveryAuthorizationId();
        timeline.record("Recovery authorization " + authorizationId + " issued -- initiating the recovery flow");
        RecoveryResponse recovery = client.initiateRecovery(authorizationId, null);
        timeline.record("Recovery " + recovery.recoveryId() + " -> status=" + recovery.status()
                + " classification=" + recovery.classification());
    }

    /**
     * Real webhook deliveries are always signed by the server, never by a consumer -- this
     * constructs a sample payload the exact way the server would (same {@code WebhookSigner}
     * algorithm) purely so {@link WebhookSignatureVerifier} can be exercised end to end without
     * requiring an authenticated webhook-subscription registration (see this class's javadoc).
     */
    private static void runWebhookVerificationDemo(EventTimeline timeline) {
        timeline.record("Constructing a sample signed webhook payload to demonstrate signature verification");
        String rawBody = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"schemaVersion\":1,"
                + "\"eventType\":\"protection.decision.made\",\"occurredAt\":\"" + Instant.now() + "\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String deliveryId = UUID.randomUUID().toString();
        String signature = new WebhookSigner().sign(DEMO_WEBHOOK_SECRET, timestamp, deliveryId, rawBody);

        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(DEMO_WEBHOOK_SECRET);
        WebhookVerificationResult first = verifier.verify(signature, timestamp, deliveryId, rawBody);
        timeline.record("First delivery " + deliveryId + " -> " + first.outcome());
        if (!first.accepted()) {
            throw new IllegalStateException("expected the first delivery to be accepted, got " + first.outcome());
        }

        WebhookVerificationResult replay = verifier.verify(signature, timestamp, deliveryId, rawBody);
        timeline.record("Replayed delivery " + deliveryId + " -> " + replay.outcome());
        if (replay.accepted()) {
            throw new IllegalStateException("expected the replayed delivery to be rejected, but it was accepted");
        }
    }

    private static String syntheticAccountReference(String slug) {
        return "demo-" + slug + "-" + UUID.randomUUID() + "@example.test";
    }
}
