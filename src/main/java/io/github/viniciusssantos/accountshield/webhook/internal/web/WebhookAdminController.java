package io.github.viniciusssantos.accountshield.webhook.internal.web;

import io.github.viniciusssantos.accountshield.webhook.CreateWebhookSubscriptionCommand;
import io.github.viniciusssantos.accountshield.webhook.WebhookSecretIssued;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionService;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookAdminController {

    private final WebhookSubscriptionService webhookSubscriptionService;

    WebhookAdminController(WebhookSubscriptionService webhookSubscriptionService) {
        this.webhookSubscriptionService = webhookSubscriptionService;
    }

    @PostMapping
    public ResponseEntity<WebhookSecretIssued> create(
            @Valid @RequestBody CreateWebhookSubscriptionRequest request, Authentication authentication) {
        WebhookSecretIssued issued = webhookSubscriptionService.create(
                new CreateWebhookSubscriptionCommand(request.url(), request.eventTypeFilter()),
                authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @GetMapping
    public List<WebhookSubscriptionView> list() {
        return webhookSubscriptionService.list();
    }

    @PostMapping("/{subscriptionId}/rotate-secret")
    public WebhookSecretIssued rotateSecret(@PathVariable UUID subscriptionId, Authentication authentication) {
        return webhookSubscriptionService.rotateSecret(subscriptionId, authentication.getName());
    }

    @PostMapping("/{subscriptionId}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID subscriptionId, Authentication authentication) {
        webhookSubscriptionService.setEnabled(subscriptionId, true, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscriptionId}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID subscriptionId, Authentication authentication) {
        webhookSubscriptionService.setEnabled(subscriptionId, false, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
