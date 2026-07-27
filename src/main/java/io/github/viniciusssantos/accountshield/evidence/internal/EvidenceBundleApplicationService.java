package io.github.viniciusssantos.accountshield.evidence.internal;

import io.github.viniciusssantos.accountshield.audit.AuditChainRecordProof;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundle;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleContent;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleService;
import io.github.viniciusssantos.accountshield.evidence.EvidenceExportCommand;
import io.github.viniciusssantos.accountshield.evidence.EvidenceManifest;
import io.github.viniciusssantos.accountshield.evidence.EvidenceVerificationResult;
import io.github.viniciusssantos.accountshield.outbox.AccountPseudonymizer;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class EvidenceBundleApplicationService implements EvidenceBundleService {

    private static final String CONTENT_HASH_ALGORITHM = "SHA-256";

    private final DecisionTraceQuery decisionTraceQuery;
    private final SimulationService simulationService;
    private final AuditChainVerificationService auditChainVerificationService;
    private final AccountPseudonymizer pseudonymizer;
    private final EvidenceBundleSigner signer;
    private final EvidenceExportAuditRecorder exportAuditRecorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    EvidenceBundleApplicationService(
            DecisionTraceQuery decisionTraceQuery,
            SimulationService simulationService,
            AuditChainVerificationService auditChainVerificationService,
            AccountPseudonymizer pseudonymizer,
            EvidenceBundleSigner signer,
            EvidenceExportAuditRecorder exportAuditRecorder,
            ObjectMapper objectMapper,
            @Qualifier("decisionClock") Clock clock) {
        this.decisionTraceQuery = decisionTraceQuery;
        this.simulationService = simulationService;
        this.auditChainVerificationService = auditChainVerificationService;
        this.pseudonymizer = pseudonymizer;
        this.signer = signer;
        this.exportAuditRecorder = exportAuditRecorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<EvidenceBundle> exportBundle(EvidenceExportCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Optional<DecisionTraceView> traceOpt =
                decisionTraceQuery.findByProtectionRequestId(command.protectionRequestId());
        if (traceOpt.isEmpty()) {
            return Optional.empty();
        }
        DecisionTraceView trace = traceOpt.get();

        ReplayResult replay = simulationService.replay(command.protectionRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "replay unavailable for a decision that was just found by the same lookup: "
                                + command.protectionRequestId()));

        AuditChainRecordProof chainProof =
                auditChainVerificationService.findProof(trace.decisionId()).orElse(null);

        EvidenceBundleContent content = new EvidenceBundleContent(
                EvidenceBundleContent.BUNDLE_SCHEMA_VERSION,
                trace.decisionId(),
                trace.protectionRequestId(),
                pseudonymizer.pseudonymize(trace.accountReference()),
                trace.requestFingerprint(),
                trace.algorithmVersion(),
                trace.policyKey(),
                trace.policyVersion(),
                trace.outcome(),
                trace.riskScore(),
                new TreeMap<>(trace.normalizedContext()),
                trace.decidedAt(),
                trace.reasons(),
                replay,
                chainProof);

        byte[] canonicalContent = serialize(content);
        String contentHash = sha256Hex(canonicalContent);
        String signature = signer.sign(canonicalContent);
        Instant now = clock.instant();

        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceBundleContent.BUNDLE_SCHEMA_VERSION,
                trace.decisionId(),
                trace.protectionRequestId(),
                now,
                command.actor(),
                command.reason(),
                CONTENT_HASH_ALGORITHM,
                contentHash,
                EvidenceBundleSigner.SIGNATURE_ALGORITHM,
                signature,
                signer.publicKeyBase64());

        exportAuditRecorder.recordExport(
                trace.decisionId(), trace.protectionRequestId(), command.actor(), command.reason(),
                contentHash, CONTENT_HASH_ALGORITHM, now);

        return Optional.of(new EvidenceBundle(manifest, content));
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceVerificationResult verify(EvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");

        List<String> problems = new ArrayList<>();
        EvidenceManifest manifest = bundle.manifest();
        EvidenceBundleContent content = bundle.content();

        if (!EvidenceBundleContent.BUNDLE_SCHEMA_VERSION.equals(content.bundleSchemaVersion())
                || !EvidenceBundleContent.BUNDLE_SCHEMA_VERSION.equals(manifest.bundleSchemaVersion())) {
            problems.add("unsupported bundle schema version");
            return EvidenceVerificationResult.failed(problems);
        }

        byte[] canonicalContent = serialize(content);
        String recomputedHash = sha256Hex(canonicalContent);
        if (!recomputedHash.equals(manifest.contentHash())) {
            problems.add("content_hash does not match the recomputed canonical content");
        }

        boolean signatureValid = EvidenceBundleSigner.verify(
                canonicalContent, manifest.signature(), manifest.signingPublicKey());
        if (!signatureValid) {
            problems.add("signature does not verify against the manifest's embedded public key");
        }

        return problems.isEmpty() ? EvidenceVerificationResult.ok() : EvidenceVerificationResult.failed(problems);
    }

    private byte[] serialize(EvidenceBundleContent content) {
        return objectMapper.writeValueAsBytes(content);
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(CONTENT_HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
