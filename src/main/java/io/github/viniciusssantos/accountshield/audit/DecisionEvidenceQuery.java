package io.github.viniciusssantos.accountshield.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Audit-owned, privacy-minimized evidence used by higher-level investigation composition. */
public interface DecisionEvidenceQuery {

    Optional<DecisionEvidence> findByDecisionReference(String decisionReference);

    record DecisionEvidence(
            DecisionEvidenceSummary decision,
            UUID protectionRequestId,
            Instant requestedAt,
            String maskedSubjectReference,
            List<ReasonEvidence> reasons,
            SignalProvenanceEvidence signalProvenance,
            PolicyProvenanceEvidence policyProvenance,
            ExecutionProvenanceEvidence executionProvenance,
            boolean partial) {

        public DecisionEvidence {
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            maskedSubjectReference = requireText(maskedSubjectReference, "maskedSubjectReference");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
            Objects.requireNonNull(signalProvenance, "signalProvenance must not be null");
            Objects.requireNonNull(policyProvenance, "policyProvenance must not be null");
            Objects.requireNonNull(executionProvenance, "executionProvenance must not be null");
        }
    }

    record DecisionEvidenceSummary(
            String decisionReference,
            String correlationId,
            String eventType,
            String outcome,
            int riskScore,
            String riskBand,
            String policyKey,
            String policyVersion,
            Instant decidedAt,
            boolean degraded,
            boolean simulated,
            boolean provenanceAvailable) {

        public DecisionEvidenceSummary {
            decisionReference = requireText(decisionReference, "decisionReference");
            correlationId = requireText(correlationId, "correlationId");
            eventType = requireText(eventType, "eventType");
            outcome = requireText(outcome, "outcome");
            if (riskScore < 0 || riskScore > 100) {
                throw new IllegalArgumentException("riskScore must be between 0 and 100");
            }
            riskBand = requireText(riskBand, "riskBand");
            policyKey = requireText(policyKey, "policyKey");
            policyVersion = requireText(policyVersion, "policyVersion");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        }
    }

    record ReasonEvidence(String code, int contribution, int ordinal) {
        public ReasonEvidence {
            code = requireText(code, "code");
            if (contribution < -100 || contribution > 100) {
                throw new IllegalArgumentException("contribution must be between -100 and 100");
            }
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
        }
    }

    record SignalProvenanceEvidence(
            String provider,
            Instant observedAt,
            String confidence,
            String schemaVersion,
            String state,
            boolean simulated,
            boolean integrityAvailable) {

        public SignalProvenanceEvidence {
            state = requireText(state, "state");
        }
    }

    record PolicyProvenanceEvidence(
            String policyKey,
            String policyVersion,
            String routingReason,
            Integer rolloutCohortBucket,
            String rolloutCandidateVersion,
            Boolean rolloutCandidateSelected) {

        public PolicyProvenanceEvidence {
            policyKey = requireText(policyKey, "policyKey");
            policyVersion = requireText(policyVersion, "policyVersion");
            routingReason = requireText(routingReason, "routingReason");
        }
    }

    record ExecutionProvenanceEvidence(
            String algorithmVersion,
            String normalizedInputSchemaVersion,
            String reasonCatalogVersion,
            String decisionEngineVersion,
            String applicationCommitSha,
            boolean canonicalInputHashAvailable,
            boolean auditRecordHashAvailable) {

        public ExecutionProvenanceEvidence {
            algorithmVersion = requireText(algorithmVersion, "algorithmVersion");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
