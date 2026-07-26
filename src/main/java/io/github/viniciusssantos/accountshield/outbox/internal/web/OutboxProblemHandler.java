package io.github.viniciusssantos.accountshield.outbox.internal.web;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotDeadLetteredException;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OutboxAdminController.class)
class OutboxProblemHandler {

    private static final URI NOT_FOUND_TYPE = URI.create("urn:accountshield:problem:outbox-event-not-found");
    private static final URI NOT_DEAD_LETTERED_TYPE =
            URI.create("urn:accountshield:problem:outbox-event-not-dead-lettered");

    @ExceptionHandler(OutboxEventNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(OutboxEventNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Outbox event " + ex.eventId() + " was not found.");
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Outbox event not found");
        problem.setProperty("code", "OUTBOX_EVENT_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(OutboxEventNotDeadLetteredException.class)
    public ResponseEntity<ProblemDetail> notDeadLettered(OutboxEventNotDeadLetteredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Outbox event " + ex.eventId() + " is " + ex.currentStatus() + ", not DEAD_LETTERED.");
        problem.setType(NOT_DEAD_LETTERED_TYPE);
        problem.setTitle("Outbox event not dead-lettered");
        problem.setProperty("code", "OUTBOX_EVENT_NOT_DEAD_LETTERED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
