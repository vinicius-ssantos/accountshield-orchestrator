package io.github.viniciusssantos.accountshield.webhook.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.webhook.CreateWebhookSubscriptionCommand;
import io.github.viniciusssantos.accountshield.webhook.WebhookSecretIssued;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionNotFoundException;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionService;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionStatus;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebhookAdminControllerTest {

    private final WebhookSubscriptionService webhookSubscriptionService = mock(WebhookSubscriptionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WebhookAdminController(webhookSubscriptionService))
                .setControllerAdvice(new WebhookProblemHandler())
                .build();
    }

    @Test
    void createReturns201WithTheSecretExactlyOnce() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        when(webhookSubscriptionService.create(any(), eq("operator-1")))
                .thenReturn(new WebhookSecretIssued(subscriptionId, "generated-secret"));

        mockMvc.perform(post("/api/v1/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(new TestingAuthenticationToken("operator-1", null))
                        .content("{\"url\":\"https://example.test/receiver\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value("generated-secret"));

        verify(webhookSubscriptionService).create(
                new CreateWebhookSubscriptionCommand("https://example.test/receiver", null), "operator-1");
    }

    @Test
    void listNeverIncludesASecretField() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        when(webhookSubscriptionService.list()).thenReturn(List.of(new WebhookSubscriptionView(
                subscriptionId, "https://example.test/receiver", null,
                WebhookSubscriptionStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), null)));

        mockMvc.perform(get("/api/v1/webhooks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].url").value("https://example.test/receiver"));
    }

    @Test
    void rotateSecretReturnsTheNewSecret() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        when(webhookSubscriptionService.rotateSecret(subscriptionId, "operator-1"))
                .thenReturn(new WebhookSecretIssued(subscriptionId, "rotated-secret"));

        mockMvc.perform(post("/api/v1/webhooks/" + subscriptionId + "/rotate-secret")
                        .principal(new TestingAuthenticationToken("operator-1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("rotated-secret"));
    }

    @Test
    void disableReturns404WhenSubscriptionUnknown() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        doThrow(new WebhookSubscriptionNotFoundException(subscriptionId))
                .when(webhookSubscriptionService).setEnabled(subscriptionId, false, "operator-1");

        mockMvc.perform(post("/api/v1/webhooks/" + subscriptionId + "/disable")
                        .principal(new TestingAuthenticationToken("operator-1", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SUBSCRIPTION_NOT_FOUND"));
    }
}
