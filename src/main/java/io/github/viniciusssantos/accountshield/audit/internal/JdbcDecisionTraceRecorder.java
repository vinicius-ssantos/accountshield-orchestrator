package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceCommand;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
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
                decided_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private static final String INSERT_REASON = """
            INSERT INTO audit.decision_reason (
                id,
                decision_id,
                code,
                contribution,
                details
            ) VALUES (?, ?, ?, ?, ?::jsonb)
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
                command.decidedAt());

        for (DecisionReasonContribution reason : command.reasons()) {
            jdbcTemplate.update(
                    INSERT_REASON,
                    UUID.randomUUID(),
                    command.decisionId(),
                    reason.code(),
                    reason.contribution(),
                    reason.details().isEmpty() ? null : toJson(reason.details()));
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("failed to serialize bounded audit context", exception);
        }
    }
}
