package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.DecisionEvidence;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.DecisionEvidenceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.ExecutionProvenanceEvidence;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.PolicyProvenanceEvidence;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.ReasonEvidence;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.SignalProvenanceEvidence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcDecisionEvidenceQuery implements DecisionEvidenceQuery {

    private static final String DETAIL_QUERY = """
            SELECT dt.id,
                   dt.protection_request_id,
                   dt.correlation_id,
                   dt.account_reference,
                   pr.event_type,
                   pr.requested_at,
                   dt.outcome,
                   dt.risk_score,
                   dt.policy_key,
                   dt.policy_version,
                   dt.algorithm_version,
                   dt.normalized_context::text AS normalized_context,
                   dt.decided_at,
                   (dt.record_hash IS NOT NULL) AS audit_record_hash_available
              FROM audit.decision_trace dt
              JOIN protection.protection_request pr
                ON pr.id = dt.protection_request_id
             WHERE dt.id = :decisionId
            """;

    private static final String REASONS_QUERY = """
            SELECT code, contribution, ordinal
              FROM audit.decision_reason
             WHERE decision_id = :decisionId
             ORDER BY ordinal NULLS LAST, code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDecisionEvidenceQuery(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DecisionEvidence> findByDecisionReference(String decisionReference) {
        UUID decisionId = parseDecisionReference(decisionReference);
        MapSqlParameterSource parameters = new MapSqlParameterSource("decisionId", decisionId);
        List<DetailRow> rows = jdbcTemplate.query(DETAIL_QUERY, parameters, this::mapDetailRow);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toEvidence(rows.getFirst()));
    }

    private DecisionEvidence toEvidence(DetailRow row) {
        Map<String, Object> context = parseContext(row.normalizedContext());
        List<ReasonEvidence> reasons = jdbcTemplate.query(
                REASONS_QUERY,
                new MapSqlParameterSource("decisionId", row.decisionId()),
                (resultSet, rowNumber) -> new ReasonEvidence(
                        resultSet.getString("code"),
                        resultSet.getInt("contribution"),
                        resultSet.getInt("ordinal")));

        boolean degraded = booleanValue(context, "degraded", false);
        boolean simulated = booleanValue(context, "signalSimulated", false);
        boolean provenanceAvailable = row.algorithmVersion() != null
                && textValue(context, "decisionEngineVersion") != null
                && textValue(context, "reasonCatalogVersion") != null;
        SignalProvenanceEvidence signal = signalProvenance(context, simulated);

        DecisionEvidenceSummary summary = new DecisionEvidenceSummary(
                row.decisionId().toString(),
                row.correlationId(),
                row.eventType(),
                row.outcome(),
                row.riskScore(),
                riskBand(row.riskScore()),
                row.policyKey(),
                row.policyVersion(),
                row.decidedAt(),
                degraded,
                simulated,
                provenanceAvailable);

        return new DecisionEvidence(
                summary,
                row.protectionRequestId(),
                row.requestedAt(),
                maskSubject(row.accountReference()),
                reasons,
                signal,
                policyProvenance(row, context),
                executionProvenance(row, context),
                !provenanceAvailable || "UNAVAILABLE".equals(signal.state()));
    }

    private SignalProvenanceEvidence signalProvenance(
            Map<String, Object> context, boolean simulated) {
        String provider = textValue(context, "signalProvider");
        Instant observedAt = instantValue(context, "signalObservedAt");
        String confidence = textValue(context, "signalConfidence");
        String schemaVersion = textValue(context, "signalSchemaVersion");
        boolean integrityAvailable = textValue(context, "signalIntegrityHash") != null;
        String state;
        if (provider == null || observedAt == null || confidence == null || schemaVersion == null) {
            state = "UNAVAILABLE";
        } else if (simulated) {
            state = "SIMULATED";
        } else {
            state = "RECORDED";
        }
        return new SignalProvenanceEvidence(
                provider,
                observedAt,
                confidence,
                schemaVersion,
                state,
                simulated,
                integrityAvailable);
    }

    private PolicyProvenanceEvidence policyProvenance(
            DetailRow row, Map<String, Object> context) {
        Integer cohortBucket = integerValue(context, "rolloutCohortBucket");
        String candidateVersion = textValue(context, "rolloutCandidateVersion");
        Boolean candidateSelected = nullableBooleanValue(context, "rolloutCandidateSelected");
        String routingReason;
        if (Boolean.TRUE.equals(candidateSelected)) {
            routingReason = "CANDIDATE_ROLLOUT";
        } else if (candidateVersion != null) {
            routingReason = "STABLE_ROLLOUT";
        } else {
            routingReason = "ACTIVE_POLICY";
        }
        return new PolicyProvenanceEvidence(
                row.policyKey(),
                row.policyVersion(),
                routingReason,
                cohortBucket,
                candidateVersion,
                candidateSelected);
    }

    private ExecutionProvenanceEvidence executionProvenance(
            DetailRow row, Map<String, Object> context) {
        return new ExecutionProvenanceEvidence(
                row.algorithmVersion(),
                textValue(context, "normalizedInputSchemaVersion"),
                textValue(context, "reasonCatalogVersion"),
                textValue(context, "decisionEngineVersion"),
                textValue(context, "applicationCommitSha"),
                textValue(context, "canonicalInputHash") != null,
                row.auditRecordHashAvailable());
    }

    private UUID parseDecisionReference(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("decisionReference must be a valid UUID");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("decisionReference must be a valid UUID");
        }
    }

    private Map<String, Object> parseContext(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new HashMap<>();
            raw.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    result.put(stringKey, value);
                }
            });
            return Map.copyOf(result);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String textValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Instant instantValue(Map<String, Object> context, String key) {
        String value = textValue(context, key);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Integer integerValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = textValue(context, key);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean booleanValue(Map<String, Object> context, String key, boolean fallback) {
        Boolean value = nullableBooleanValue(context, key);
        return value == null ? fallback : value;
    }

    private Boolean nullableBooleanValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = textValue(context, key);
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private String maskSubject(String accountReference) {
        if (accountReference == null || accountReference.isBlank()) {
            return "masked-subject";
        }
        int suffixLength = Math.min(4, accountReference.length());
        return "••••" + accountReference.substring(accountReference.length() - suffixLength);
    }

    private DetailRow mapDetailRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DetailRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("protection_request_id", UUID.class),
                resultSet.getString("correlation_id"),
                resultSet.getString("account_reference"),
                resultSet.getString("event_type"),
                resultSet.getTimestamp("requested_at").toInstant(),
                resultSet.getString("outcome"),
                resultSet.getInt("risk_score"),
                resultSet.getString("policy_key"),
                resultSet.getString("policy_version"),
                resultSet.getString("algorithm_version"),
                resultSet.getString("normalized_context"),
                resultSet.getTimestamp("decided_at").toInstant(),
                resultSet.getBoolean("audit_record_hash_available"));
    }

    private String riskBand(int score) {
        if (score >= 70) {
            return "HIGH";
        }
        if (score >= 30) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private record DetailRow(
            UUID decisionId,
            UUID protectionRequestId,
            String correlationId,
            String accountReference,
            String eventType,
            Instant requestedAt,
            String outcome,
            int riskScore,
            String policyKey,
            String policyVersion,
            String algorithmVersion,
            String normalizedContext,
            Instant decidedAt,
            boolean auditRecordHashAvailable) {
    }
}
