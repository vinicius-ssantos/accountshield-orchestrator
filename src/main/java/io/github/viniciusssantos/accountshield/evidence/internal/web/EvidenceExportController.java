package io.github.viniciusssantos.accountshield.evidence.internal.web;

import io.github.viniciusssantos.accountshield.evidence.EvidenceBundle;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleService;
import io.github.viniciusssantos.accountshield.evidence.EvidenceExportCommand;
import io.github.viniciusssantos.accountshield.evidence.EvidenceVerificationResult;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evidence")
class EvidenceExportController {

    private final EvidenceBundleService evidenceBundleService;

    EvidenceExportController(EvidenceBundleService evidenceBundleService) {
        this.evidenceBundleService = evidenceBundleService;
    }

    @PostMapping("/export")
    public ResponseEntity<EvidenceBundle> export(
            @RequestBody ExportRequest request, Authentication authentication) {
        EvidenceExportCommand command = new EvidenceExportCommand(
                request.protectionRequestId(), authentication.getName(), request.reason());
        return evidenceBundleService.exportBundle(command)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/verify")
    public EvidenceVerificationResult verify(@RequestBody EvidenceBundle bundle) {
        return evidenceBundleService.verify(bundle);
    }

    record ExportRequest(UUID protectionRequestId, String reason) {
    }
}
