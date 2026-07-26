package io.github.viniciusssantos.accountshield.outbox.internal.web;

import io.github.viniciusssantos.accountshield.outbox.OutboxAdminService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/outbox")
class OutboxAdminController {

    private final OutboxAdminService outboxAdminService;

    OutboxAdminController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    @PostMapping("/{eventId}/requeue")
    public ResponseEntity<Void> requeue(@PathVariable UUID eventId, Authentication authentication) {
        outboxAdminService.requeue(eventId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
