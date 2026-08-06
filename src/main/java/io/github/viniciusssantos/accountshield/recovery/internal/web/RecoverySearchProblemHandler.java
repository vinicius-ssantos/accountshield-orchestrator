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

@RestControllerAdvice(assignableTypes = RecoverySearchController.class)
public class RecoverySearchProblemHandler {

    private static final URI INVALID_TYPE =
            URI.create("urn:accountshield:problem:invalid-recovery-search");
    private static final URI UNAVAILABLE_TYPE =
            URI.create("urn:accountshield:problem:recovery-search-unavailable");

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> invalidSearch(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The recovery search request is invalid or outside the supported bounds.");
        problem.setType(INVALID_TYPE);
        problem.setTitle("Recovery search rejected");
        problem.setProperty("code", "INVALID_RECOVERY_SEARCH");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Recovery search is temporarily unavailable.");
        problem.setType(UNAVAILABLE_TYPE);
        problem.setTitle("Recovery search unavailable");
        problem.setProperty("code", "RECOVERY_SEARCH_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
