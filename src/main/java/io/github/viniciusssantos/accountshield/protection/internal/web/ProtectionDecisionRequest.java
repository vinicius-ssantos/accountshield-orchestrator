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
        @Min(0) @Max(20) int failedAttempts,
        boolean newDevice,
        boolean impossibleTravel,
        boolean compromisedCredential,
        NetworkRiskLevel networkRiskLevel) {

    public ProtectionDecisionRequest {
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
                        networkRiskLevel));
    }
}
