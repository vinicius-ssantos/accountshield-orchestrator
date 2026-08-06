package io.github.viniciusssantos.accountshieldcli.scenario;

import io.github.viniciusssantos.accountshieldsdk.model.NetworkRiskLevel;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionEventType;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionOutcome;
import io.github.viniciusssantos.accountshieldsdk.model.SignalConfidence;
import java.util.List;

/**
 * One deterministic, named adversarial scenario -- the exact 5 scenarios and hand-verified
 * expected score/outcome/reason-codes from issue #54's scenario lab (ADR 0034), reused here rather
 * than re-derived, so `scenario run` and the server-side scenario lab test can never quietly
 * drift apart.
 */
public record ScenarioDefinition(
        String name,
        String description,
        ProtectionEventType eventType,
        int failedAttempts,
        boolean newDevice,
        boolean impossibleTravel,
        boolean compromisedCredential,
        NetworkRiskLevel networkRiskLevel,
        SignalConfidence signalConfidence,
        int expectedScore,
        ProtectionOutcome expectedOutcome,
        List<String> expectedReasonCodes) {

    public static final List<ScenarioDefinition> ALL = List.of(
            new ScenarioDefinition(
                    "credential-stuffing",
                    "10 failed attempts, compromised credential, new device, medium-risk network",
                    ProtectionEventType.LOGIN_ATTEMPT, 10, true, false, true, NetworkRiskLevel.MEDIUM,
                    SignalConfidence.HIGH, 95, ProtectionOutcome.TEMPORARILY_BLOCK,
                    List.of("COMPROMISED_CREDENTIAL", "FAILED_ATTEMPTS", "NETWORK_RISK_MEDIUM", "NEW_DEVICE")),
            new ScenarioDefinition(
                    "impossible-travel",
                    "impossible travel signal, new device, medium-risk network",
                    ProtectionEventType.LOGIN_ATTEMPT, 0, true, true, false, NetworkRiskLevel.MEDIUM,
                    SignalConfidence.HIGH, 60, ProtectionOutcome.REQUIRE_STEP_UP,
                    List.of("IMPOSSIBLE_TRAVEL", "NETWORK_RISK_MEDIUM", "NEW_DEVICE")),
            new ScenarioDefinition(
                    "device-takeover",
                    "unfamiliar device, 3 failed attempts, medium-risk network, low-confidence signal",
                    ProtectionEventType.LOGIN_ATTEMPT, 3, true, false, false, NetworkRiskLevel.MEDIUM,
                    SignalConfidence.LOW, 44, ProtectionOutcome.REQUIRE_STEP_UP,
                    List.of("FAILED_ATTEMPTS", "NETWORK_RISK_MEDIUM", "NEW_DEVICE", "LOW_CONFIDENCE_SIGNAL")),
            new ScenarioDefinition(
                    "mfa-fatigue",
                    "5 failed attempts, new device, high-risk network, then repeated wrong MFA codes",
                    ProtectionEventType.LOGIN_ATTEMPT, 5, true, false, false, NetworkRiskLevel.HIGH,
                    SignalConfidence.HIGH, 50, ProtectionOutcome.REQUIRE_STEP_UP,
                    List.of("FAILED_ATTEMPTS", "NETWORK_RISK_HIGH", "NEW_DEVICE")),
            new ScenarioDefinition(
                    "recovery-abuse",
                    "password-reset attempt, compromised credential, impossible travel",
                    ProtectionEventType.PASSWORD_RESET_ATTEMPT, 0, false, true, true, NetworkRiskLevel.LOW,
                    SignalConfidence.HIGH, 75, ProtectionOutcome.START_RECOVERY,
                    List.of("COMPROMISED_CREDENTIAL", "IMPOSSIBLE_TRAVEL")));

    public static ScenarioDefinition byName(String name) {
        return ALL.stream()
                .filter(scenario -> scenario.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown scenario: " + name + " (run 'scenario list' to see available names)"));
    }
}
