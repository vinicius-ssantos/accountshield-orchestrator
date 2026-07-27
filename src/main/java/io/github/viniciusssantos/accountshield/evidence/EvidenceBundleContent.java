package io.github.viniciusssantos.accountshield.evidence;

import io.github.viniciusssantos.accountshield.audit.AuditChainRecordProof;
import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The canonical, redacted evidence for one decision. Field order is fixed by this record's
 * declaration (Jackson serializes records in declared-field order) and {@code normalizedContext}
 * is coerced into a {@link SortedMap} so two exports of the same historical decision always
 * serialize to byte-identical canonical JSON, regardless of map insertion order.
 */
public record EvidenceBundleContent(
        String bundleSchemaVersion,
        UUID decisionId,
        UUID protectionRequestId,
        String pseudonymizedAccountReference,
        String requestFingerprint,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        String outcome,
        int riskScore,
        SortedMap<String, Object> normalizedContext,
        Instant decidedAt,
        List<DecisionReasonContribution> reasons,
        ReplayResult replay,
        AuditChainRecordProof chainProof) {

    public static final String BUNDLE_SCHEMA_VERSION = "evidence-bundle-1.0";

    public EvidenceBundleContent {
        normalizedContext = new TreeMap<>(Map.copyOf(normalizedContext));
        reasons = List.copyOf(reasons);
    }
}
