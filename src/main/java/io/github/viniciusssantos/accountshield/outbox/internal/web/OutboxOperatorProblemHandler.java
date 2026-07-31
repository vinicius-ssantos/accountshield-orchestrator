package io.github.viniciusssantos.accountshield.outbox.internal.web;

import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OutboxOperatorController.class)
class OutboxOperatorProblemHandler {

    private static final URI INVALID_TYPE = URI.create("urn:accountshield:problem:invalid-outbox-search");
    private static final URI UNAVAILABLE_TYPE = URI.create("urn:accountshield:problem:outbox-search-unavailable");

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> invalidSearch(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The outbox search request is invalid or outside the supported bounds.");
        problem.setType(INVALID_TYPE);
        problem.setTitle("Outbox search rejected");
        problem.setProperty("code", "INVALID_OUTBOX_SEARCH");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Outbox search is temporarily unavailable.");
        problem.setType(UNAVAILABLE_TYPE);
        problem.setTitle("Outbox search unavailable");
        problem.setProperty("code", "OUTBOX_SEARCH_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
