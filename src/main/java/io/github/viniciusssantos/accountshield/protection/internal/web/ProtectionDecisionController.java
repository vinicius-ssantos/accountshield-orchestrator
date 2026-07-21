package io.github.viniciusssantos.accountshield.protection.internal.web;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/protection-decisions")
public class ProtectionDecisionController {

    private final ProtectionDecisionService protectionDecisionService;

    public ProtectionDecisionController(ProtectionDecisionService protectionDecisionService) {
        this.protectionDecisionService = protectionDecisionService;
    }

    @PostMapping
    public ResponseEntity<ProtectionDecisionResponse> decide(
            @Valid @RequestBody ProtectionDecisionRequest request) {
        var result = protectionDecisionService.decide(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProtectionDecisionResponse.from(result));
    }
}
