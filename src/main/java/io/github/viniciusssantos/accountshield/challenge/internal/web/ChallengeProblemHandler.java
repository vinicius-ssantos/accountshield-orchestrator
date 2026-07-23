package io.github.viniciusssantos.accountshield.challenge.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
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
    private static final URI USE_REJECTED_TYPE =
            URI.create("urn:accountshield:problem:challenge-use-rejected");

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

    @ExceptionHandler(ChallengeUseRejectedException.class)
    public ResponseEntity<ProblemDetail> useRejected(ChallengeUseRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The challenge cannot be used for the requested operation.");
        problem.setType(USE_REJECTED_TYPE);
        problem.setTitle("Challenge use rejected");
        problem.setProperty("code", "CHALLENGE_USE_REJECTED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
