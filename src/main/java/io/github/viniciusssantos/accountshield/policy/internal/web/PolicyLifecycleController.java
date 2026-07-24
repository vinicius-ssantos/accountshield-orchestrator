package io.github.viniciusssantos.accountshield.policy.internal.web;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyLifecycleController {

    private final PolicyLifecycleService lifecycleService;

    PolicyLifecycleController(PolicyLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    public ResponseEntity<PolicyVersionSummary> createDraft(@Valid @RequestBody CreateDraftRequest request) {
        PolicyVersionSummary summary = lifecycleService.createDraft(new CreatePolicyCommand(
                request.policyKey(),
                request.version(),
                request.allowMaxScore(),
                request.stepUpMaxScore(),
                request.recoveryMaxScore() == null ? (short) 89 : request.recoveryMaxScore()));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @PostMapping("/{policyKey}/{version}/validate")
    public ResponseEntity<PolicyVersionSummary> validate(
            @PathVariable String policyKey,
            @PathVariable String version) {
        return ResponseEntity.ok(lifecycleService.validate(policyKey, version));
    }

    @PostMapping("/{policyKey}/{version}/activate/step-up")
    public ResponseEntity<StepUpChallengeResponse> requestActivationStepUp(
            @PathVariable String policyKey,
            @PathVariable String version,
            Authentication authentication) {
        UUID challengeId = lifecycleService.requestActivationStepUp(policyKey, version, authentication.getName());
        return ResponseEntity.ok(new StepUpChallengeResponse(challengeId));
    }

    @PostMapping("/{policyKey}/{version}/activate")
    public ResponseEntity<PolicyVersionSummary> activate(
            @PathVariable String policyKey,
            @PathVariable String version,
            @Valid @RequestBody StepUpRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(lifecycleService.activate(
                policyKey, version, request.stepUpChallengeId(), authentication.getName()));
    }

    @PostMapping("/{policyKey}/{version}/reject")
    public ResponseEntity<PolicyVersionSummary> reject(
            @PathVariable String policyKey,
            @PathVariable String version) {
        return ResponseEntity.ok(lifecycleService.reject(policyKey, version));
    }

    @PostMapping("/{policyKey}/{version}/retire/step-up")
    public ResponseEntity<StepUpChallengeResponse> requestRetirementStepUp(
            @PathVariable String policyKey,
            @PathVariable String version,
            Authentication authentication) {
        UUID challengeId = lifecycleService.requestRetirementStepUp(policyKey, version, authentication.getName());
        return ResponseEntity.ok(new StepUpChallengeResponse(challengeId));
    }

    @PostMapping("/{policyKey}/{version}/retire")
    public ResponseEntity<PolicyVersionSummary> retire(
            @PathVariable String policyKey,
            @PathVariable String version,
            @Valid @RequestBody StepUpRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(lifecycleService.retire(
                policyKey, version, request.stepUpChallengeId(), authentication.getName()));
    }

    record CreateDraftRequest(
            @NotBlank String policyKey,
            @NotBlank String version,
            @Min(0) @Max(99) short allowMaxScore,
            @Min(1) @Max(99) short stepUpMaxScore,
            @Schema(description = "Highest recovery-request score that may produce START_RECOVERY", example = "89")
            @Min(0) @Max(99) Short recoveryMaxScore) {
    }

    record StepUpRequest(
            @Schema(description = "Challenge ID returned by the matching .../step-up endpoint, "
                    + "after it has been verified via POST /api/v1/challenges/{id}/verify")
            @NotNull UUID stepUpChallengeId) {
    }

    record StepUpChallengeResponse(UUID challengeId) {
    }
}
