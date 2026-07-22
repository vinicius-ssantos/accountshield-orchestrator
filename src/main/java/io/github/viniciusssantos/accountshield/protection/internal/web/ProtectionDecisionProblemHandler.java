package io.github.viniciusssantos.accountshield.protection.internal.web;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.RateLimitExceededException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ProtectionDecisionProblemHandler {

    private static final URI INVALID_REQUEST_TYPE = URI.create("urn:accountshield:problem:invalid-request");
    private static final URI POLICY_UNAVAILABLE_TYPE = URI.create("urn:accountshield:problem:policy-unavailable");
    private static final URI CONFLICT_TYPE = URI.create("urn:accountshield:problem:idempotency-conflict");
    private static final URI RATE_LIMIT_TYPE = URI.create("urn:accountshield:problem:rate-limit-exceeded");

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request contains unsupported or out-of-range values.");
        problem.setType(INVALID_REQUEST_TYPE);
        problem.setTitle("Invalid protection decision request");
        problem.setProperty("code", "INVALID_PROTECTION_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ActivePolicyUnavailableException.class)
    public ResponseEntity<ProblemDetail> policyUnavailable(ActivePolicyUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "A protection decision cannot be produced safely at this time.");
        problem.setType(POLICY_UNAVAILABLE_TYPE);
        problem.setTitle("Protection policy unavailable");
        problem.setProperty("code", "ACTIVE_POLICY_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(ConflictingIdempotencyRequestException.class)
    public ResponseEntity<ProblemDetail> idempotencyConflict(ConflictingIdempotencyRequestException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A previous request with the same idempotency key produced a different result.");
        problem.setType(CONFLICT_TYPE);
        problem.setTitle("Conflicting idempotency request");
        problem.setProperty("code", "IDEMPOTENCY_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimitExceeded(RateLimitExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many protection requests for this account.");
        problem.setType(RATE_LIMIT_TYPE);
        problem.setTitle("Rate limit exceeded");
        problem.setProperty("code", "RATE_LIMIT_EXCEEDED");
        problem.setProperty("retryAfter", exception.retryAfter().toString());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
    }
}
