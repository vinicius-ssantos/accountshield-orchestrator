package io.github.viniciusssantos.accountshield.audit.internal.web;

import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DecisionInvestigationController.class)
public class DecisionInvestigationProblemHandler {

    private static final URI INVALID_TYPE =
            URI.create("urn:accountshield:problem:invalid-decision-investigation");
    private static final URI NOT_FOUND_TYPE =
            URI.create("urn:accountshield:problem:decision-investigation-not-found");
    private static final URI UNAVAILABLE_TYPE =
            URI.create("urn:accountshield:problem:decision-investigation-unavailable");

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The decision investigation request is invalid or outside the supported bounds.");
        problem.setType(INVALID_TYPE);
        problem.setTitle("Decision investigation rejected");
        problem.setProperty("code", "INVALID_DECISION_SEARCH");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DecisionInvestigationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(DecisionInvestigationNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The requested decision investigation is unavailable.");
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Decision investigation unavailable");
        problem.setProperty("code", "DECISION_INVESTIGATION_NOT_FOUND");
        problem.setProperty("retryable", false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Decision investigation is temporarily unavailable.");
        problem.setType(UNAVAILABLE_TYPE);
        problem.setTitle("Decision investigation unavailable");
        problem.setProperty("code", "DECISION_SEARCH_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
