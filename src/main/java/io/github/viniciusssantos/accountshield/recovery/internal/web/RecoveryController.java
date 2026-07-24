package io.github.viniciusssantos.accountshield.recovery.internal.web;

import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recovery")
class RecoveryController {

    private final RecoveryService recoveryService;

    RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping
    public ResponseEntity<RecoveryResponse> initiate(@Valid @RequestBody InitiateRecoveryRequest request) {
        RecoveryFlow flow = recoveryService.initiate(
                new InitiateRecoveryCommand(request.authorizationId()));
        return ResponseEntity.ok(RecoveryResponse.from(flow));
    }

    @PostMapping("/{recoveryId}/confirm-identity")
    public ResponseEntity<RecoveryResponse> confirmIdentity(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody ConfirmIdentityRequest request) {
        RecoveryFlow flow = recoveryService.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, request.challengeId()));
        return ResponseEntity.ok(RecoveryResponse.from(flow));
    }

    @PostMapping("/{recoveryId}/complete")
    public ResponseEntity<RecoveryResponse> complete(@PathVariable UUID recoveryId) {
        RecoveryFlow flow = recoveryService.complete(recoveryId);
        return ResponseEntity.ok(RecoveryResponse.from(flow));
    }

    @PostMapping("/{recoveryId}/review")
    public ResponseEntity<RecoveryResponse> review(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody RecoveryReviewRequest request,
            Authentication authentication) {
        RecoveryFlow flow = recoveryService.review(new RecoveryReviewCommand(
                recoveryId, RecoveryReviewDecision.valueOf(request.decision()), authentication.getName()));
        return ResponseEntity.ok(RecoveryResponse.from(flow));
    }

    record InitiateRecoveryRequest(
            @Schema(
                    description = "Unexpired recovery authorization returned by a START_RECOVERY decision",
                    example = "550e8400-e29b-41d4-a716-446655440000")
            @NotNull UUID authorizationId) {
    }

    record ConfirmIdentityRequest(@NotNull UUID challengeId) {
    }

    record RecoveryReviewRequest(@NotBlank String decision) {
    }

    record RecoveryResponse(
            UUID recoveryId,
            String accountReference,
            String eventType,
            String status,
            String classification,
            String classificationRuleVersion,
            UUID identityChallengeId,
            Instant initiatedAt,
            Instant updatedAt,
            Instant eligibleAfter,
            UUID authorizationId,
            UUID protectionRequestId,
            UUID originatingDecisionId) {

        static RecoveryResponse from(RecoveryFlow flow) {
            return new RecoveryResponse(
                    flow.recoveryId(),
                    flow.accountReference(),
                    flow.eventType().name(),
                    flow.status().name(),
                    flow.classification().name(),
                    flow.classificationRuleVersion(),
                    flow.identityChallengeId(),
                    flow.initiatedAt(),
                    flow.updatedAt(),
                    flow.eligibleAfter(),
                    flow.authorizationId(),
                    flow.protectionRequestId(),
                    flow.originatingDecisionId());
        }
    }
}
