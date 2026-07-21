package io.github.viniciusssantos.accountshield.challenge.internal.web;

import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ChallengeController.class)
class ChallengeProblemHandler {

    private static final URI INVALID_STATE_TYPE =
            URI.create("urn:accountshield:problem:invalid-challenge-state");

    @ExceptionHandler(InvalidChallengeStateException.class)
    public ResponseEntity<ProblemDetail> invalidState(InvalidChallengeStateException ex) {
        HttpStatus status = ex.currentStatus() == ChallengeStatus.EXPIRED
                ? HttpStatus.GONE
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                "The challenge is in state " + ex.currentStatus() + " and cannot accept this operation.");
        problem.setType(INVALID_STATE_TYPE);
        problem.setTitle("Invalid challenge state");
        problem.setProperty("code", "INVALID_CHALLENGE_STATE");
        return ResponseEntity.status(status).body(problem);
    }
}
