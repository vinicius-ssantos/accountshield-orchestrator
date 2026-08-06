package io.github.viniciusssantos.accountshield.audit.internal.web;

import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/chain")
class AuditChainController {

    private final AuditChainVerificationService auditChainVerificationService;

    AuditChainController(AuditChainVerificationService auditChainVerificationService) {
        this.auditChainVerificationService = auditChainVerificationService;
    }

    @GetMapping("/verify")
    public AuditChainVerificationResult verify(
            @RequestParam long from, @RequestParam long to) {
        return auditChainVerificationService.verifyRange(from, to);
    }

    @GetMapping("/root-hash")
    public ResponseEntity<AuditChainRootHash> rootHash() {
        return auditChainVerificationService.currentRootHash()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
