package io.github.viniciusssantos.accountshield.audit.internal.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuditChainController.class)
class AuditChainProblemHandler {

    private static final URI INVALID_RANGE_TYPE = URI.create("urn:accountshield:problem:audit-chain-invalid-range");

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRange(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(INVALID_RANGE_TYPE);
        problem.setTitle("Invalid audit chain verification range");
        problem.setProperty("code", "AUDIT_CHAIN_INVALID_RANGE");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
