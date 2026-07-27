package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.AuditChainBreak;
import io.github.viniciusssantos.accountshield.audit.AuditChainHasher;
import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditChainVerificationApplicationService implements AuditChainVerificationService {

    private static final String SELECT_RANGE = """
            SELECT id, protection_request_id, account_reference, request_fingerprint,
                   algorithm_version, policy_key, policy_version, outcome, risk_score, decided_at,
                   chain_sequence, previous_hash, record_hash, canonical_schema_version
            FROM audit.decision_trace
            WHERE chain_sequence BETWEEN ? AND ?
            ORDER BY chain_sequence ASC
            """;

    private static final String SELECT_HASH_AT_SEQUENCE = """
            SELECT record_hash FROM audit.decision_trace WHERE chain_sequence = ?
            """;

    private static final String SELECT_REASONS = """
            SELECT code, contribution FROM audit.decision_reason
            WHERE decision_id = ? ORDER BY ordinal
            """;

    private static final String SELECT_TIP = """
            SELECT chain_sequence, record_hash, decided_at FROM audit.decision_trace
            ORDER BY chain_sequence DESC LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuditChainVerificationApplicationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public AuditChainVerificationResult verifyRange(long fromSequenceInclusive, long toSequenceInclusive) {
        if (fromSequenceInclusive < 1 || toSequenceInclusive < fromSequenceInclusive) {
            throw new IllegalArgumentException(
                    "invalid range: [" + fromSequenceInclusive + ", " + toSequenceInclusive + "]");
        }

        List<TraceRow> rows = jdbcTemplate.query(SELECT_RANGE, TRACE_ROW_MAPPER, fromSequenceInclusive, toSequenceInclusive);

        List<AuditChainBreak> breaks = new ArrayList<>();
        long expectedPreviousSequence = fromSequenceInclusive - 1;
        String expectedPreviousHash = hashAtSequence(expectedPreviousSequence);

        for (TraceRow row : rows) {
            if (row.chainSequence() != expectedPreviousSequence + 1) {
                breaks.add(new AuditChainBreak(row.chainSequence(),
                        "gap in chain sequence: expected " + (expectedPreviousSequence + 1)
                                + " but found " + row.chainSequence()));
            } else if (!Objects.equals(row.previousHash(), expectedPreviousHash)) {
                breaks.add(new AuditChainBreak(row.chainSequence(), "previous_hash does not link to the prior record"));
            } else {
                verifyRecordHash(row, breaks);
            }
            expectedPreviousSequence = row.chainSequence();
            expectedPreviousHash = row.recordHash();
        }

        return new AuditChainVerificationResult(rows.size(), breaks.isEmpty(), breaks);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditChainRootHash> currentRootHash() {
        return jdbcTemplate.query(SELECT_TIP, (rs, rowNum) -> new AuditChainRootHash(
                        rs.getLong("chain_sequence"), rs.getString("record_hash"),
                        rs.getTimestamp("decided_at").toInstant()))
                .stream()
                .findFirst();
    }

    private void verifyRecordHash(TraceRow row, List<AuditChainBreak> breaks) {
        List<DecisionReasonContribution> reasons = fetchReasons(row.id());
        try {
            String expectedHash = AuditChainHasher.computeRecordHash(
                    row.canonicalSchemaVersion(),
                    row.chainSequence(),
                    row.previousHash(),
                    row.id(),
                    row.protectionRequestId(),
                    row.accountReference(),
                    row.requestFingerprint(),
                    row.algorithmVersion(),
                    row.policyKey(),
                    row.policyVersion(),
                    row.outcome(),
                    row.riskScore(),
                    row.decidedAt(),
                    reasons);
            if (!expectedHash.equals(row.recordHash())) {
                breaks.add(new AuditChainBreak(row.chainSequence(), "record_hash does not match recomputed content"));
            }
        } catch (IllegalArgumentException exception) {
            breaks.add(new AuditChainBreak(row.chainSequence(), exception.getMessage()));
        }
    }

    private List<DecisionReasonContribution> fetchReasons(UUID decisionId) {
        return jdbcTemplate.query(SELECT_REASONS,
                (rs, rowNum) -> new DecisionReasonContribution(rs.getString("code"), rs.getInt("contribution"), Map.of()),
                decisionId);
    }

    private String hashAtSequence(long chainSequence) {
        if (chainSequence < 1) {
            return null;
        }
        return jdbcTemplate.query(SELECT_HASH_AT_SEQUENCE, (rs, rowNum) -> rs.getString("record_hash"), chainSequence)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static final org.springframework.jdbc.core.RowMapper<TraceRow> TRACE_ROW_MAPPER = (rs, rowNum) -> new TraceRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("protection_request_id"),
            rs.getString("account_reference"),
            rs.getString("request_fingerprint"),
            rs.getString("algorithm_version"),
            rs.getString("policy_key"),
            rs.getString("policy_version"),
            rs.getString("outcome"),
            rs.getInt("risk_score"),
            toInstant(rs.getTimestamp("decided_at")),
            rs.getLong("chain_sequence"),
            rs.getString("previous_hash"),
            rs.getString("record_hash"),
            rs.getString("canonical_schema_version"));

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record TraceRow(
            UUID id,
            UUID protectionRequestId,
            String accountReference,
            String requestFingerprint,
            String algorithmVersion,
            String policyKey,
            String policyVersion,
            String outcome,
            int riskScore,
            Instant decidedAt,
            long chainSequence,
            String previousHash,
            String recordHash,
            String canonicalSchemaVersion) {
    }
}
