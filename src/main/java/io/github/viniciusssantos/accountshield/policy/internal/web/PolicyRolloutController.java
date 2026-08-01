package io.github.viniciusssantos.accountshield.policy.internal.web;

import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyRolloutController {

    private final PolicyRolloutService rolloutService;

    PolicyRolloutController(PolicyRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    @PostMapping("/{policyKey}/rollout/step-up")
    public ResponseEntity<PolicyLifecycleController.StepUpChallengeResponse> requestRolloutStepUp(
            @PathVariable String policyKey,
            @Valid @RequestBody StartRolloutStepUpRequest request,
            Authentication authentication) {
        UUID challengeId = rolloutService.requestRolloutStepUp(
                policyKey, request.candidateVersion(), authentication.getName());
        return ResponseEntity.ok(new PolicyLifecycleController.StepUpChallengeResponse(challengeId, null));
    }

    @PostMapping("/{policyKey}/rollout")
    public ResponseEntity<PolicyRollout> startRollout(
            @PathVariable String policyKey,
            @Valid @RequestBody StartRolloutRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(rolloutService.startRollout(
                policyKey,
                request.candidateVersion(),
                request.rolloutPercentage(),
                request.stepUpChallengeId(),
                authentication.getName()));
    }

    @PatchMapping("/{policyKey}/rollout/step-up")
    public ResponseEntity<PolicyLifecycleController.StepUpChallengeResponse> requestPercentageUpdateStepUp(
            @PathVariable String policyKey,
            Authentication authentication) {
        UUID challengeId = rolloutService.requestPercentageUpdateStepUp(policyKey, authentication.getName());
        return ResponseEntity.ok(new PolicyLifecycleController.StepUpChallengeResponse(challengeId, null));
    }

    @PatchMapping("/{policyKey}/rollout")
    public ResponseEntity<PolicyRollout> updatePercentage(
            @PathVariable String policyKey,
            @Valid @RequestBody UpdateRolloutRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(rolloutService.updatePercentage(
                policyKey, request.rolloutPercentage(), request.stepUpChallengeId(), authentication.getName()));
    }

    @PostMapping("/{policyKey}/rollout/rollback")
    public ResponseEntity<PolicyRollout> rollback(
            @PathVariable String policyKey,
            Authentication authentication) {
        return ResponseEntity.ok(rolloutService.rollback(policyKey, authentication.getName()));
    }

    @GetMapping("/{policyKey}/rollout")
    public ResponseEntity<PolicyRollout> status(@PathVariable String policyKey) {
        return ResponseEntity.ok(rolloutService.findActiveRollout(policyKey)
                .orElseThrow(() -> new PolicyRolloutNotFoundException(policyKey)));
    }

    record StartRolloutStepUpRequest(@NotBlank String candidateVersion) {
    }

    record StartRolloutRequest(
            @NotBlank String candidateVersion,
            @Min(0) @Max(100) int rolloutPercentage,
            @NotNull UUID stepUpChallengeId) {
    }

    record UpdateRolloutRequest(
            @Min(0) @Max(100) int rolloutPercentage,
            @NotNull UUID stepUpChallengeId) {
    }
}
