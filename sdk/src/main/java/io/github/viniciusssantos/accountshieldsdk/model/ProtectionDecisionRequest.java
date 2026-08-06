package io.github.viniciusssantos.accountshieldsdk.model;

import java.time.Instant;

/**
 * Mirrors {@code POST /api/v1/protection-decisions}' request body exactly (field names, nullability,
 * and defaulting match {@code ProtectionDecisionRequest} on the server -- verified against the real
 * source, not guessed; see {@code SdkContractVerificationTest} on the server for a live round-trip
 * proof). {@code idempotencyKey} is a body field, not a header.
 */
public record ProtectionDecisionRequest(
        String accountReference,
        ProtectionEventType eventType,
        Integer failedAttempts,
        Boolean newDevice,
        Boolean impossibleTravel,
        Boolean compromisedCredential,
        NetworkRiskLevel networkRiskLevel,
        String signalProvider,
        Instant signalObservedAt,
        SignalConfidence signalConfidence,
        String idempotencyKey,
        String clientId) {

    public static Builder builder(String accountReference, ProtectionEventType eventType) {
        return new Builder(accountReference, eventType);
    }

    /** Fluent builder -- every field beyond the two required ones is optional and server-defaulted. */
    public static final class Builder {
        private final String accountReference;
        private final ProtectionEventType eventType;
        private Integer failedAttempts;
        private Boolean newDevice;
        private Boolean impossibleTravel;
        private Boolean compromisedCredential;
        private NetworkRiskLevel networkRiskLevel;
        private String signalProvider;
        private Instant signalObservedAt;
        private SignalConfidence signalConfidence;
        private String idempotencyKey;
        private String clientId;

        private Builder(String accountReference, ProtectionEventType eventType) {
            this.accountReference = accountReference;
            this.eventType = eventType;
        }

        public Builder failedAttempts(int failedAttempts) {
            this.failedAttempts = failedAttempts;
            return this;
        }

        public Builder newDevice(boolean newDevice) {
            this.newDevice = newDevice;
            return this;
        }

        public Builder impossibleTravel(boolean impossibleTravel) {
            this.impossibleTravel = impossibleTravel;
            return this;
        }

        public Builder compromisedCredential(boolean compromisedCredential) {
            this.compromisedCredential = compromisedCredential;
            return this;
        }

        public Builder networkRiskLevel(NetworkRiskLevel networkRiskLevel) {
            this.networkRiskLevel = networkRiskLevel;
            return this;
        }

        public Builder signalProvider(String signalProvider) {
            this.signalProvider = signalProvider;
            return this;
        }

        public Builder signalObservedAt(Instant signalObservedAt) {
            this.signalObservedAt = signalObservedAt;
            return this;
        }

        public Builder signalConfidence(SignalConfidence signalConfidence) {
            this.signalConfidence = signalConfidence;
            return this;
        }

        /**
         * Setting this is what makes {@link io.github.viniciusssantos.accountshieldsdk.AccountShieldClient}'s
         * retry policy treat {@code decide()} as safe to retry -- see {@code RetryPolicy}'s javadoc.
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public ProtectionDecisionRequest build() {
            return new ProtectionDecisionRequest(
                    accountReference, eventType, failedAttempts, newDevice, impossibleTravel,
                    compromisedCredential, networkRiskLevel, signalProvider, signalObservedAt,
                    signalConfidence, idempotencyKey, clientId);
        }
    }
}
