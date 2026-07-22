package io.github.viniciusssantos.accountshield.policy.internal.web;

import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PolicyLifecycleController.class)
class PolicyLifecycleProblemHandler {

    private static final URI ILLEGAL_TRANSITION_TYPE =
            URI.create("urn:accountshield:problem:illegal-policy-transition");
    private static final URI CONFLICT_TYPE =
            URI.create("urn:accountshield:problem:policy-conflict");

    @ExceptionHandler(IllegalPolicyTransitionException.class)
    public ResponseEntity<ProblemDetail> illegalTransition(IllegalPolicyTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(ILLEGAL_TRANSITION_TYPE);
        problem.setTitle("Illegal policy transition");
        problem.setProperty("code", "ILLEGAL_TRANSITION");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> conflict(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(CONFLICT_TYPE);
        problem.setTitle("Policy conflict");
        problem.setProperty("code", "CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
