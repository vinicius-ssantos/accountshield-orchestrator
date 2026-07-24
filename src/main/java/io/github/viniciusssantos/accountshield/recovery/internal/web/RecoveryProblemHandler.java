package io.github.viniciusssantos.accountshield.recovery.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowConflictException;
import io.github.viniciusssantos.accountshield.recovery.UnauthorizedRecoveryInitiationException;
import io.github.viniciusssantos.accountshield.recovery.UnknownRecoveryClassificationRuleException;
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
    private static final URI UNAUTHORIZED_TYPE =
            URI.create("urn:accountshield:problem:unauthorized-recovery-initiation");
    private static final URI FLOW_CONFLICT_TYPE =
            URI.create("urn:accountshield:problem:recovery-flow-conflict");
    private static final URI UNKNOWN_RULE_VERSION_TYPE =
            URI.create("urn:accountshield:problem:unknown-classification-rule-version");
    private static final URI STEP_UP_INVALID_TYPE =
            URI.create("urn:accountshield:problem:invalid-challenge-state");
    private static final URI STEP_UP_REJECTED_TYPE =
            URI.create("urn:accountshield:problem:challenge-use-rejected");

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

    @ExceptionHandler(UnauthorizedRecoveryInitiationException.class)
    public ResponseEntity<ProblemDetail> unauthorized(UnauthorizedRecoveryInitiationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "The recovery request cannot be authorized.");
        problem.setType(UNAUTHORIZED_TYPE);
        problem.setTitle("Unauthorized recovery initiation");
        problem.setProperty("code", "UNAUTHORIZED_RECOVERY_INITIATION");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(RecoveryFlowConflictException.class)
    public ResponseEntity<ProblemDetail> flowConflict(RecoveryFlowConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The recovery flow was concurrently modified by another request. Retry with the latest state.");
        problem.setType(FLOW_CONFLICT_TYPE);
        problem.setTitle("Recovery flow conflict");
        problem.setProperty("code", "RECOVERY_FLOW_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(UnknownRecoveryClassificationRuleException.class)
    public ResponseEntity<ProblemDetail> unknownClassificationRule(UnknownRecoveryClassificationRuleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "This recovery flow cannot be advanced safely because its classification rule version is unknown.");
        problem.setType(UNKNOWN_RULE_VERSION_TYPE);
        problem.setTitle("Unknown recovery classification rule version");
        problem.setProperty("code", "UNKNOWN_CLASSIFICATION_RULE_VERSION");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(InvalidChallengeStateException.class)
    public ResponseEntity<ProblemDetail> stepUpInvalidState(InvalidChallengeStateException ex) {
        HttpStatus status = ex.currentStatus() == ChallengeStatus.EXPIRED
                ? HttpStatus.GONE
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                "The step-up challenge is in state " + ex.currentStatus() + " and cannot authorize this action.");
        problem.setType(STEP_UP_INVALID_TYPE);
        problem.setTitle("Invalid step-up challenge state");
        problem.setProperty("code", "INVALID_CHALLENGE_STATE");
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(ChallengeUseRejectedException.class)
    public ResponseEntity<ProblemDetail> stepUpRejected(ChallengeUseRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The step-up challenge cannot authorize this action.");
        problem.setType(STEP_UP_REJECTED_TYPE);
        problem.setTitle("Step-up challenge rejected");
        problem.setProperty("code", "CHALLENGE_USE_REJECTED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
