package io.github.viniciusssantos.accountshield.recovery;

import java.util.UUID;

public class UnknownRecoveryClassificationRuleException extends RuntimeException {

    private final UUID recoveryId;
    private final String ruleVersion;

    public UnknownRecoveryClassificationRuleException(UUID recoveryId, String ruleVersion) {
        super("Recovery " + recoveryId + " was classified with unknown rule version " + ruleVersion);
        this.recoveryId = recoveryId;
        this.ruleVersion = ruleVersion;
    }

    public UUID recoveryId() {
        return recoveryId;
    }

    public String ruleVersion() {
        return ruleVersion;
    }
}
