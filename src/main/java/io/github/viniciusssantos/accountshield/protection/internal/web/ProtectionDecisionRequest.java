package io.github.viniciusssantos.accountshield.protection.internal.web;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProtectionDecisionRequest(
        @NotBlank @Size(max = 128) String accountReference,
        @NotNull ProtectionEventType eventType,
        @Min(0) @Max(20) Integer failedAttempts,
        Boolean newDevice,
        Boolean impossibleTravel,
        Boolean compromisedCredential,
        NetworkRiskLevel networkRiskLevel,
        @Size(max = 128) String idempotencyKey) {

    public ProtectionDecisionRequest {
        failedAttempts = failedAttempts == null ? 0 : failedAttempts;
        newDevice = Boolean.TRUE.equals(newDevice);
        impossibleTravel = Boolean.TRUE.equals(impossibleTravel);
        compromisedCredential = Boolean.TRUE.equals(compromisedCredential);
        networkRiskLevel = networkRiskLevel == null ? NetworkRiskLevel.LOW : networkRiskLevel;
    }

    ProtectionDecisionCommand toCommand() {
        return new ProtectionDecisionCommand(
                accountReference,
                eventType,
                new RiskSignals(
                        failedAttempts,
                        newDevice,
                        impossibleTravel,
                        compromisedCredential,
                        networkRiskLevel),
                idempotencyKey);
    }
}
