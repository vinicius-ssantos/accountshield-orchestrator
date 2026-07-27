package io.github.viniciusssantos.accountshield.evidence.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records who exported an evidence bundle and why, into the append-only
 * {@code audit.evidence_export_log} table (V24). Plain JdbcTemplate, not a JPA repository: this
 * insert has no bean-construction-time dependents, so there is no risk of the circular-dependency
 * trap a {@code @Component}-registered JPA {@code AttributeConverter} can create -- it is simply
 * the same direct style {@code JdbcDecisionTraceRecorder} already uses for the table it appends to.
 */
@Component
class EvidenceExportAuditRecorder {

    private static final String INSERT_EXPORT_LOG = """
            INSERT INTO audit.evidence_export_log (
                id, decision_id, protection_request_id, exported_by, export_reason,
                content_hash, content_hash_algorithm, exported_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    EvidenceExportAuditRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void recordExport(
            UUID decisionId, UUID protectionRequestId, String exportedBy, String exportReason,
            String contentHash, String contentHashAlgorithm, Instant exportedAt) {
        jdbcTemplate.update(
                INSERT_EXPORT_LOG,
                UUID.randomUUID(),
                decisionId,
                protectionRequestId,
                exportedBy,
                exportReason,
                contentHash,
                contentHashAlgorithm,
                Timestamp.from(exportedAt));
    }
}
