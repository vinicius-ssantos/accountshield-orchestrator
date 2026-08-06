package io.github.viniciusssantos.accountshieldsdk.webhook;

public record WebhookVerificationResult(WebhookVerificationOutcome outcome, String deliveryId) {

    public boolean accepted() {
        return outcome == WebhookVerificationOutcome.ACCEPTED;
    }
}
