package io.github.viniciusssantos.accountshield.evidence.internal.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EvidenceExportController.class)
class EvidenceProblemHandler {

    private static final URI INVALID_REQUEST_TYPE = URI.create("urn:accountshield:problem:evidence-invalid-request");

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(INVALID_REQUEST_TYPE);
        problem.setTitle("Invalid evidence export request");
        problem.setProperty("code", "EVIDENCE_INVALID_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
