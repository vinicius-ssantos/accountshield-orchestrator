package io.github.viniciusssantos.accountshield.protection.internal.web;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ProtectionDecisionRequest(
        @NotBlank @Size(max = 128) String accountReference,
        @NotNull ProtectionEventType eventType,
        @Min(0) @Max(20) Integer failedAttempts,
        Boolean newDevice,
        Boolean impossibleTravel,
        Boolean compromisedCredential,
        NetworkRiskLevel networkRiskLevel,
        @Size(max = 100) String signalProvider,
        Instant signalObservedAt,
        SignalConfidence signalConfidence,
        @Size(max = 128) String idempotencyKey) {

    private static final String DEFAULT_PROVIDER = "CLIENT_SUPPLIED";

    public ProtectionDecisionRequest {
        failedAttempts = failedAttempts == null ? 0 : failedAttempts;
        newDevice = Boolean.TRUE.equals(newDevice);
        impossibleTravel = Boolean.TRUE.equals(impossibleTravel);
        compromisedCredential = Boolean.TRUE.equals(compromisedCredential);
        networkRiskLevel = networkRiskLevel == null ? NetworkRiskLevel.LOW : networkRiskLevel;
        signalProvider = signalProvider == null || signalProvider.isBlank() ? DEFAULT_PROVIDER : signalProvider;
        signalConfidence = signalConfidence == null ? SignalConfidence.HIGH : signalConfidence;
    }

    ProtectionDecisionCommand toCommand() {
        return new ProtectionDecisionCommand(
                accountReference,
                eventType,
                new RiskSignalEnvelope(
                        new RiskSignals(
                                failedAttempts,
                                newDevice,
                                impossibleTravel,
                                compromisedCredential,
                                networkRiskLevel),
                        signalProvider,
                        signalObservedAt == null ? Instant.now() : signalObservedAt,
                        signalConfidence,
                        null,
                        true),
                idempotencyKey);
    }
}
