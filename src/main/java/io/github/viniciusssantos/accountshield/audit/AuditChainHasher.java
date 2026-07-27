package io.github.viniciusssantos.accountshield.audit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The canonical, deterministic hash of one {@code audit.decision_trace} row's content plus its
 * position in the tamper-evident chain (its sequence number and the previous row's hash) and its
 * associated {@code audit.decision_reason} children in ordinal order. Shared between the write
 * path ({@code JdbcDecisionTraceRecorder}) and the verification path
 * ({@code AuditChainVerificationService}) so both can never drift, mirroring {@code
 * protection.RequestFingerprint}'s hand-rolled fixed-field-order approach (ADR 0020).
 *
 * <p>{@link #CANONICAL_SCHEMA_VERSION} is stored on every row precisely so a future change to
 * this byte layout does not silently break verification of history written under the old one;
 * verification dispatches on the stored version rather than assuming the current one.
 */
public final class AuditChainHasher {

    public static final String ALGORITHM = "SHA-256";
    public static final String CANONICAL_SCHEMA_VERSION = "audit-chain-1.0";

    private AuditChainHasher() {
    }

    public static String computeRecordHash(
            String canonicalSchemaVersion,
            long chainSequence,
            String previousHash,
            UUID decisionId,
            UUID protectionRequestId,
            String accountReference,
            String requestFingerprint,
            String algorithmVersion,
            String policyKey,
            String policyVersion,
            String outcome,
            int riskScore,
            Instant decidedAt,
            List<DecisionReasonContribution> reasons) {
        if (!CANONICAL_SCHEMA_VERSION.equals(canonicalSchemaVersion)) {
            throw new IllegalArgumentException("unknown canonical schema version: " + canonicalSchemaVersion);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(canonicalSchemaVersion);
                output.writeLong(chainSequence);
                output.writeUTF(previousHash == null ? "" : previousHash);
                output.writeUTF(decisionId.toString());
                output.writeUTF(protectionRequestId.toString());
                output.writeUTF(accountReference);
                output.writeUTF(requestFingerprint);
                output.writeUTF(algorithmVersion);
                output.writeUTF(policyKey);
                output.writeUTF(policyVersion);
                output.writeUTF(outcome);
                output.writeInt(riskScore);
                output.writeLong(decidedAt.toEpochMilli());
                output.writeInt(reasons.size());
                for (DecisionReasonContribution reason : reasons) {
                    output.writeUTF(reason.code());
                    output.writeInt(reason.contribution());
                }
            }
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("failed to compute audit chain record hash", exception);
        }
    }
}
