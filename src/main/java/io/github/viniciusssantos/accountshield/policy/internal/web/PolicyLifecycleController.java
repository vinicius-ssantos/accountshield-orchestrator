package io.github.viniciusssantos.accountshield.policy.internal.web;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
                request.stepUpMaxScore()));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @PostMapping("/{policyKey}/{version}/validate")
    public ResponseEntity<PolicyVersionSummary> validate(
            @PathVariable String policyKey,
            @PathVariable String version) {
        return ResponseEntity.ok(lifecycleService.validate(policyKey, version));
    }

    @PostMapping("/{policyKey}/{version}/activate")
    public ResponseEntity<PolicyVersionSummary> activate(
            @PathVariable String policyKey,
            @PathVariable String version) {
        return ResponseEntity.ok(lifecycleService.activate(policyKey, version));
    }

    @PostMapping("/{policyKey}/{version}/reject")
    public ResponseEntity<PolicyVersionSummary> reject(
            @PathVariable String policyKey,
            @PathVariable String version) {
        return ResponseEntity.ok(lifecycleService.reject(policyKey, version));
    }

    @PostMapping("/{policyKey}/{version}/retire")
    public ResponseEntity<PolicyVersionSummary> retire(
            @PathVariable String policyKey,
            @PathVariable String version) {
        return ResponseEntity.ok(lifecycleService.retire(policyKey, version));
    }

    @ExceptionHandler(IllegalPolicyTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(IllegalPolicyTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ILLEGAL_TRANSITION", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", ex.getMessage()));
    }

    record CreateDraftRequest(
            @NotBlank String policyKey,
            @NotBlank String version,
            @Min(0) @Max(99) short allowMaxScore,
            @Min(1) @Max(99) short stepUpMaxScore) {
    }

    record ErrorResponse(String code, String message) {
    }
}
