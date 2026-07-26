package io.github.viniciusssantos.accountshield.webhook.internal.web;

import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WebhookAdminController.class)
class WebhookProblemHandler {

    private static final URI NOT_FOUND_TYPE =
            URI.create("urn:accountshield:problem:webhook-subscription-not-found");

    @ExceptionHandler(WebhookSubscriptionNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(WebhookSubscriptionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Webhook subscription " + ex.subscriptionId() + " was not found.");
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Webhook subscription not found");
        problem.setProperty("code", "WEBHOOK_SUBSCRIPTION_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
