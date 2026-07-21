package io.github.viniciusssantos.accountshield.recovery.internal.web;

import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecoveryController.class)
class RecoveryProblemHandler {

    private static final URI INVALID_STATE_TYPE =
            URI.create("urn:accountshield:problem:invalid-recovery-state");

    @ExceptionHandler(InvalidRecoveryStateException.class)
    public ResponseEntity<ProblemDetail> invalidState(InvalidRecoveryStateException ex) {
        HttpStatus status = ex.currentStatus().name().equals("DELAYED")
                ? HttpStatus.CONFLICT
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                "The recovery flow is in state " + ex.currentStatus()
                        + " and cannot accept action: " + ex.attemptedAction());
        problem.setType(INVALID_STATE_TYPE);
        problem.setTitle("Invalid recovery state");
        problem.setProperty("code", "INVALID_RECOVERY_STATE");
        return ResponseEntity.status(status).body(problem);
    }
}
