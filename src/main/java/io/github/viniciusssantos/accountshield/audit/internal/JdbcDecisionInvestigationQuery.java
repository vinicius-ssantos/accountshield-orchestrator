package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.ChallengeSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationCriteria;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationDetail;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionReasonSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionTimelineEntry;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.ExecutionProvenanceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.InvestigationSections;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.OutboxSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.PolicyProvenanceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.RecoverySummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.SignalProvenanceSummary;
import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery;
import io.github.viniciusssantos.accountshield.outbox.OutboxInvestigationQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryInvestigationQuery;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcDecisionInvestigationQuery implements DecisionInvestigationQuery {

    private static final String BASE_QUERY = """
            SELECT dt.id,
                   dt.correlation_id,
                   pr.event_type,
                   dt.outcome,
                   dt.risk_score,
                   dt.policy_key,
                   dt.policy_version,
                   dt.decided_at,
                   CASE
                       WHEN dt.normalized_context ->> 'degraded' IN ('true', 'false')
                           THEN (dt.normalized_context ->> 'degraded')::boolean
                       ELSE false
                   END AS degraded,
                   CASE
                       WHEN dt.normalized_context ->> 'signalSimulated' IN ('true', 'false')
                           THEN (dt.normalized_context ->> 'signalSimulated')::boolean
                       ELSE false
                   END AS simulated,
                   (dt.algorithm_version IS NOT NULL
                       AND jsonb_exists(dt.normalized_context, 'decisionEngineVersion')
                       AND jsonb_exists(dt.normalized_context, 'reasonCatalogVersion')) AS provenance_available
              FROM audit.decision_trace dt
              JOIN protection.protection_request pr
                ON pr.id = dt.protection_request_id
             WHERE 1 = 1
            """;

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
    private final ChallengeInvestigationQuery challengeQuery;
    private final RecoveryInvestigationQuery recoveryQuery;
    private final OutboxInvestigationQuery outboxQuery;

    public JdbcDecisionInvestigationQuery(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ChallengeInvestigationQuery challengeQuery,
            RecoveryInvestigationQuery recoveryQuery,
            OutboxInvestigationQuery outboxQuery) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.challengeQuery = challengeQuery;
        this.recoveryQuery = recoveryQuery;
        this.outboxQuery = outboxQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionInvestigationPage search(DecisionInvestigationCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");

        StringBuilder sql = new StringBuilder(BASE_QUERY);
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        addTextFilter(sql, parameters, "dt.correlation_id", "correlationId", criteria.correlationId());
        addTextFilter(sql, parameters, "pr.event_type", "eventType", criteria.eventType());
        addTextFilter(sql, parameters, "dt.outcome", "outcome", criteria.outcome());
        addTextFilter(sql, parameters, "dt.policy_version", "policyVersion", criteria.policyVersion());
        addRiskBandFilter(sql, criteria.riskBand());

        if (criteria.decidedFrom() != null) {
            sql.append(" AND dt.decided_at >= :decidedFrom\n");
            parameters.addValue("decidedFrom", Timestamp.from(criteria.decidedFrom()));
        }
        if (criteria.decidedTo() != null) {
            sql.append(" AND dt.decided_at < :decidedTo\n");
            parameters.addValue("decidedTo", Timestamp.from(criteria.decidedTo()));
        }

        DecisionCursor cursor = criteria.cursor() == null ? null : DecisionCursor.decode(criteria.cursor());
        if (cursor != null) {
            sql.append("""
                     AND (dt.decided_at < :cursorDecidedAt
                          OR (dt.decided_at = :cursorDecidedAt AND dt.id < :cursorDecisionId))
                    """);
            parameters.addValue("cursorDecidedAt", Timestamp.from(cursor.decidedAt()));
            parameters.addValue("cursorDecisionId", cursor.decisionId());
        }

        sql.append(" ORDER BY dt.decided_at DESC, dt.id DESC LIMIT :limit");
        parameters.addValue("limit", criteria.pageSize() + 1);

        List<DecisionInvestigationSummary> fetched = jdbcTemplate.query(
                sql.toString(), parameters, this::mapSummary);
        boolean hasMore = fetched.size() > criteria.pageSize();
        List<DecisionInvestigationSummary> decisions = hasMore
                ? new ArrayList<>(fetched.subList(0, criteria.pageSize()))
                : fetched;
        String nextCursor = hasMore && !decisions.isEmpty()
                ? DecisionCursor.from(decisions.get(decisions.size() - 1)).encode()
                : null;

        return new DecisionInvestigationPage(decisions, nextCursor, criteria.pageSize(), hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DecisionInvestigationDetail> investigate(String decisionReference) {
        UUID decisionId = parseDecisionReference(decisionReference);
        MapSqlParameterSource parameters = new MapSqlParameterSource("decisionId", decisionId);
        List<DetailRow> rows = jdbcTemplate.query(DETAIL_QUERY, parameters, this::mapDetailRow);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assembleDetail(rows.getFirst()));
    }

    private DecisionInvestigationDetail assembleDetail(DetailRow row) {
        Map<String, Object> context = parseContext(row.normalizedContext());
        List<DecisionReasonSummary> reasons = loadReasons(row.decisionId());
        List<ChallengeSummary> challenges = challengeQuery.findByContextId(row.protectionRequestId()).stream()
                .map(view -> new ChallengeSummary(
                        view.reference().toString(),
                        view.challengeType(),
                        view.purpose(),
                        view.status(),
                        view.createdAt(),
                        view.expiresAt(),
                        view.consumedAt()))
                .toList();
        RecoverySummary recovery = recoveryQuery.findByDecisionId(row.decisionId())
                .map(view -> new RecoverySummary(
                        view.reference().toString(),
                        view.directive(),
                        view.status(),
                        view.issuedAt(),
                        view.expiresAt(),
                        view.consumedAt()))
                .orElse(null);
        List<OutboxSummary> outboxEvents = outboxQuery
                .findByDecisionReference(row.decisionId().toString()).stream()
                .map(view -> new OutboxSummary(
                        view.reference(),
                        view.eventType(),
                        view.status(),
                        view.occurredAt(),
                        view.publishedAt(),
                        view.deadLetteredAt(),
                        view.attemptCount()))
                .toList();

        boolean degraded = booleanValue(context, "degraded", false);
        boolean simulated = booleanValue(context, "signalSimulated", false);
        boolean provenanceAvailable = row.algorithmVersion() != null
                && textValue(context, "decisionEngineVersion") != null
                && textValue(context, "reasonCatalogVersion") != null;

        DecisionInvestigationSummary summary = new DecisionInvestigationSummary(
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

        SignalProvenanceSummary signal = signalProvenance(context, simulated);
        PolicyProvenanceSummary policy = policyProvenance(row, context);
        ExecutionProvenanceSummary execution = executionProvenance(row, context);
        InvestigationSections sections = sections(row.outcome(), challenges, recovery, outboxEvents);
        List<DecisionTimelineEntry> timeline = timeline(row, challenges, recovery, outboxEvents);
        boolean partial = !provenanceAvailable
                || "UNAVAILABLE".equals(signal.state())
                || sections.challenge() == SectionAvailability.UNAVAILABLE
                || sections.recovery() == SectionAvailability.UNAVAILABLE
                || sections.outbox() == SectionAvailability.UNAVAILABLE;

        return new DecisionInvestigationDetail(
                summary,
                maskSubject(row.accountReference()),
                reasons,
                signal,
                policy,
                execution,
                challenges,
                recovery,
                outboxEvents,
                timeline,
                sections,
                partial);
    }

    private SignalProvenanceSummary signalProvenance(
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
        return new SignalProvenanceSummary(
                provider,
                observedAt,
                confidence,
                schemaVersion,
                state,
                simulated,
                integrityAvailable);
    }

    private PolicyProvenanceSummary policyProvenance(
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
        return new PolicyProvenanceSummary(
                row.policyKey(),
                row.policyVersion(),
                routingReason,
                cohortBucket,
                candidateVersion,
                candidateSelected);
    }

    private ExecutionProvenanceSummary executionProvenance(
            DetailRow row, Map<String, Object> context) {
        return new ExecutionProvenanceSummary(
                row.algorithmVersion(),
                textValue(context, "normalizedInputSchemaVersion"),
                textValue(context, "reasonCatalogVersion"),
                textValue(context, "decisionEngineVersion"),
                textValue(context, "applicationCommitSha"),
                textValue(context, "canonicalInputHash") != null,
                row.auditRecordHashAvailable());
    }

    private InvestigationSections sections(
            String outcome,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents) {
        SectionAvailability challengeAvailability;
        if (!challenges.isEmpty()) {
            challengeAvailability = SectionAvailability.AVAILABLE;
        } else if ("REQUIRE_STEP_UP".equals(outcome)) {
            challengeAvailability = SectionAvailability.UNAVAILABLE;
        } else {
            challengeAvailability = SectionAvailability.NOT_APPLICABLE;
        }

        SectionAvailability recoveryAvailability;
        if (recovery != null) {
            recoveryAvailability = SectionAvailability.AVAILABLE;
        } else if ("START_RECOVERY".equals(outcome)) {
            recoveryAvailability = SectionAvailability.UNAVAILABLE;
        } else {
            recoveryAvailability = SectionAvailability.NOT_APPLICABLE;
        }

        SectionAvailability outboxAvailability = outboxEvents.isEmpty()
                ? SectionAvailability.UNAVAILABLE
                : SectionAvailability.AVAILABLE;
        return new InvestigationSections(
                challengeAvailability, recoveryAvailability, outboxAvailability);
    }

    private List<DecisionTimelineEntry> timeline(
            DetailRow row,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents) {
        List<DecisionTimelineEntry> entries = new ArrayList<>();
        entries.add(new DecisionTimelineEntry(
                row.protectionRequestId().toString(),
                "REQUEST_RECEIVED",
                "RECEIVED",
                row.requestedAt()));
        entries.add(new DecisionTimelineEntry(
                row.decisionId().toString(),
                "DECISION_RECORDED",
                row.outcome(),
                row.decidedAt()));

        for (ChallengeSummary challenge : challenges) {
            entries.add(new DecisionTimelineEntry(
                    challenge.reference(),
                    "CHALLENGE_CREATED",
                    "CREATED",
                    challenge.createdAt()));
            if (challenge.consumedAt() != null) {
                entries.add(new DecisionTimelineEntry(
                        challenge.reference(),
                        "CHALLENGE_CONSUMED",
                        "CONSUMED",
                        challenge.consumedAt()));
            }
        }

        if (recovery != null) {
            entries.add(new DecisionTimelineEntry(
                    recovery.reference(),
                    "RECOVERY_AUTHORIZATION_ISSUED",
                    "ISSUED",
                    recovery.issuedAt()));
            if (recovery.consumedAt() != null) {
                entries.add(new DecisionTimelineEntry(
                        recovery.reference(),
                        "RECOVERY_AUTHORIZATION_CONSUMED",
                        "CONSUMED",
                        recovery.consumedAt()));
            }
        }

        for (OutboxSummary event : outboxEvents) {
            entries.add(new DecisionTimelineEntry(
                    event.reference(),
                    "OUTBOX_EVENT_RECORDED",
                    "RECORDED",
                    event.occurredAt()));
            if (event.publishedAt() != null) {
                entries.add(new DecisionTimelineEntry(
                        event.reference(),
                        "OUTBOX_EVENT_PUBLISHED",
                        "PUBLISHED",
                        event.publishedAt()));
            }
            if (event.deadLetteredAt() != null) {
                entries.add(new DecisionTimelineEntry(
                        event.reference(),
                        "OUTBOX_EVENT_DEAD_LETTERED",
                        "DEAD_LETTERED",
                        event.deadLetteredAt()));
            }
        }

        entries.sort(Comparator
                .comparing(DecisionTimelineEntry::occurredAt)
                .thenComparingInt(entry -> timelinePriority(entry.kind()))
                .thenComparing(DecisionTimelineEntry::reference));
        return List.copyOf(entries);
    }

    private int timelinePriority(String kind) {
        return switch (kind) {
            case "REQUEST_RECEIVED" -> 10;
            case "CHALLENGE_CREATED" -> 20;
            case "DECISION_RECORDED" -> 30;
            case "RECOVERY_AUTHORIZATION_ISSUED" -> 40;
            case "OUTBOX_EVENT_RECORDED" -> 50;
            case "CHALLENGE_CONSUMED" -> 60;
            case "RECOVERY_AUTHORIZATION_CONSUMED" -> 70;
            case "OUTBOX_EVENT_PUBLISHED" -> 80;
            case "OUTBOX_EVENT_DEAD_LETTERED" -> 90;
            default -> 100;
        };
    }

    private List<DecisionReasonSummary> loadReasons(UUID decisionId) {
        return jdbcTemplate.query(
                REASONS_QUERY,
                new MapSqlParameterSource("decisionId", decisionId),
                (resultSet, rowNumber) -> new DecisionReasonSummary(
                        resultSet.getString("code"),
                        resultSet.getInt("contribution"),
                        resultSet.getInt("ordinal")));
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

    private void addTextFilter(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            String column,
            String parameter,
            String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = :").append(parameter).append('\n');
            parameters.addValue(parameter, value);
        }
    }

    private void addRiskBandFilter(StringBuilder sql, String riskBand) {
        if (riskBand == null) {
            return;
        }
        switch (riskBand) {
            case "LOW" -> sql.append(" AND dt.risk_score < 30\n");
            case "MEDIUM" -> sql.append(" AND dt.risk_score BETWEEN 30 AND 69\n");
            case "HIGH" -> sql.append(" AND dt.risk_score >= 70\n");
            default -> throw new IllegalArgumentException("unsupported riskBand");
        }
    }

    private DecisionInvestigationSummary mapSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        int riskScore = resultSet.getInt("risk_score");
        return new DecisionInvestigationSummary(
                resultSet.getObject("id", UUID.class).toString(),
                resultSet.getString("correlation_id"),
                resultSet.getString("event_type"),
                resultSet.getString("outcome"),
                riskScore,
                riskBand(riskScore),
                resultSet.getString("policy_key"),
                resultSet.getString("policy_version"),
                resultSet.getTimestamp("decided_at").toInstant(),
                resultSet.getBoolean("degraded"),
                resultSet.getBoolean("simulated"),
                resultSet.getBoolean("provenance_available"));
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

    private record DecisionCursor(Instant decidedAt, UUID decisionId) {

        private static DecisionCursor from(DecisionInvestigationSummary summary) {
            return new DecisionCursor(summary.decidedAt(), UUID.fromString(summary.decisionReference()));
        }

        private String encode() {
            String raw = decidedAt + "|" + decisionId;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static DecisionCursor decode(String value) {
            try {
                String raw = new String(
                        Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("invalid decision search cursor");
                }
                return new DecisionCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid decision search cursor");
            }
        }
    }
}
