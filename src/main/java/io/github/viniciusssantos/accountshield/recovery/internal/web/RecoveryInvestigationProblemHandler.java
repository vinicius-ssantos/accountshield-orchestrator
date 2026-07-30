package io.github.viniciusssantos.accountshield.recovery.internal.web;

import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecoveryInvestigationController.class)
public class RecoveryInvestigationProblemHandler {

    private static final URI INVALID_TYPE =
            URI.create("urn:accountshield:problem:invalid-recovery-investigation");
    private static final URI NOT_FOUND_TYPE =
            URI.create("urn:accountshield:problem:recovery-investigation-not-found");
    private static final URI UNAVAILABLE_TYPE =
            URI.create("urn:accountshield:problem:recovery-investigation-unavailable");

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> invalidInvestigation(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The recovery investigation request is invalid.");
        problem.setType(INVALID_TYPE);
        problem.setTitle("Recovery investigation rejected");
        problem.setProperty("code", "INVALID_RECOVERY_INVESTIGATION");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(RecoveryInvestigationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(RecoveryInvestigationNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The requested recovery investigation was not found.");
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Recovery investigation not found");
        problem.setProperty("code", "RECOVERY_INVESTIGATION_NOT_FOUND");
        problem.setProperty("retryable", false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Recovery investigation is temporarily unavailable.");
        problem.setType(UNAVAILABLE_TYPE);
        problem.setTitle("Recovery investigation unavailable");
        problem.setProperty("code", "RECOVERY_INVESTIGATION_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
