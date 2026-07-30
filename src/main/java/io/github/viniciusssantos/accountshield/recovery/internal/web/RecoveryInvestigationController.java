package io.github.viniciusssantos.accountshield.recovery.internal.web;

import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.ChallengeEvidence;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.recovery.internal.web.RecoverySearchController.RecoverySummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/recoveries")
public class RecoveryInvestigationController {

    private static final String RECOVERY_REFERENCE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";

    private final RecoveryOperationsQuery query;

    public RecoveryInvestigationController(RecoveryOperationsQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "investigateRecovery",
            summary = "Retrieve one authorized privacy-minimized recovery investigation view")
    @PostMapping("/investigate")
    public ResponseEntity<RecoveryInvestigationResponse> investigate(
            @Valid @RequestBody RecoveryInvestigationRequest request) {
        RecoveryDetail detail = query.investigate(request.recoveryReference())
                .orElseThrow(RecoveryInvestigationNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RecoveryInvestigationResponse.from(detail));
    }

    public record RecoveryInvestigationRequest(
            @NotBlank
            @Size(max = 36)
            @Pattern(regexp = RECOVERY_REFERENCE_PATTERN)
            String recoveryReference) {
    }

    public record RecoveryInvestigationResponse(
            RecoverySummaryResponse recovery,
            String protectionRequestReference,
            boolean reviewerPresent,
            List<ChallengeEvidence> challenges,
            SectionAvailability challengeAvailability,
            boolean partial) {

        static RecoveryInvestigationResponse from(RecoveryDetail detail) {
            return new RecoveryInvestigationResponse(
                    RecoverySummaryResponse.from(detail.recovery()),
                    detail.protectionRequestReference(),
                    detail.reviewerPresent(),
                    detail.challenges(),
                    detail.challengeAvailability(),
                    detail.partial());
        }
    }
}
