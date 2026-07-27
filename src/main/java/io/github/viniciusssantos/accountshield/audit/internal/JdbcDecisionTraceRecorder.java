package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.AuditChainHasher;
import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceCommand;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcDecisionTraceRecorder implements DecisionTraceRecorder {

    /**
     * Arbitrary fixed key scoping a Postgres advisory transaction lock to "appending the next
     * link of the audit hash chain". Held for the duration of this method's transaction
     * (released automatically at commit/rollback) so concurrent decisions are serialized into
     * one unambiguous chain rather than racing on which row is "last" -- including when the
     * chain is currently empty, where a row-level lock on "the last row" would have nothing to
     * lock against.
     */
    private static final long CHAIN_LOCK_KEY = 8842017332157841L;

    private static final String LOCK_CHAIN = "SELECT pg_advisory_xact_lock(?)";

    private static final String SELECT_LAST_LINK = """
            SELECT chain_sequence, record_hash FROM audit.decision_trace
            ORDER BY chain_sequence DESC LIMIT 1
            """;

    private static final String INSERT_TRACE = """
            INSERT INTO audit.decision_trace (
                id,
                protection_request_id,
                account_reference,
                request_fingerprint,
                algorithm_version,
                policy_key,
                policy_version,
                outcome,
                risk_score,
                normalized_context,
                decided_at,
                chain_sequence,
                previous_hash,
                record_hash,
                hash_algorithm,
                canonical_schema_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_REASON = """
            INSERT INTO audit.decision_reason (
                id,
                decision_id,
                code,
                contribution,
                ordinal,
                details
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDecisionTraceRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(DecisionTraceCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        jdbcTemplate.queryForList(LOCK_CHAIN, CHAIN_LOCK_KEY);
        ChainLink lastLink = lockLastLink();
        long chainSequence = lastLink.sequence() + 1;
        String previousHash = lastLink.hash();
        String recordHash = AuditChainHasher.computeRecordHash(
                AuditChainHasher.CANONICAL_SCHEMA_VERSION,
                chainSequence,
                previousHash,
                command.decisionId(),
                command.protectionRequestId(),
                command.accountReference(),
                command.requestFingerprint(),
                command.algorithmVersion(),
                command.policyKey(),
                command.policyVersion(),
                command.outcome(),
                command.riskScore(),
                command.decidedAt(),
                command.reasons());

        jdbcTemplate.update(
                INSERT_TRACE,
                command.decisionId(),
                command.protectionRequestId(),
                command.accountReference(),
                command.requestFingerprint(),
                command.algorithmVersion(),
                command.policyKey(),
                command.policyVersion(),
                command.outcome(),
                command.riskScore(),
                toJson(command.normalizedContext()),
                Timestamp.from(command.decidedAt()),
                chainSequence,
                previousHash,
                recordHash,
                AuditChainHasher.ALGORITHM,
                AuditChainHasher.CANONICAL_SCHEMA_VERSION);

        for (int ordinal = 0; ordinal < command.reasons().size(); ordinal++) {
            DecisionReasonContribution reason = command.reasons().get(ordinal);
            jdbcTemplate.update(
                    INSERT_REASON,
                    UUID.randomUUID(),
                    command.decisionId(),
                    reason.code(),
                    reason.contribution(),
                    ordinal,
                    reason.details().isEmpty() ? null : toJson(reason.details()));
        }
    }

    private ChainLink lockLastLink() {
        List<ChainLink> rows = jdbcTemplate.query(
                SELECT_LAST_LINK,
                (rs, rowNum) -> new ChainLink(rs.getLong("chain_sequence"), rs.getString("record_hash")));
        return rows.stream().findFirst().orElse(new ChainLink(0L, null));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("failed to serialize bounded audit context", exception);
        }
    }

    private record ChainLink(long sequence, String hash) {
    }
}
