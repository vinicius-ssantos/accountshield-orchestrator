package io.github.viniciusssantos.accountshield.challenge.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/challenges")
class ChallengeController {

    private final ChallengeService challengeService;

    ChallengeController(ChallengeService challengeService) {
        this.challengeService = challengeService;
    }

    @PostMapping("/{challengeId}/verify")
    public ResponseEntity<ChallengeVerificationResponse> verify(
            @PathVariable UUID challengeId,
            @Valid @RequestBody ChallengeVerificationRequest request) {
        ChallengeResult result = challengeService.verify(new ChallengeVerificationCommand(
                challengeId,
                request.providedCode(),
                request.purpose(),
                request.contextId()));
        return ResponseEntity.ok(ChallengeVerificationResponse.from(result));
    }
}
