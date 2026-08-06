package io.github.viniciusssantos.accountshield.investigation.internal.web;

import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DecisionTimelineController.class)
public class DecisionTimelineProblemHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The decision investigation request is invalid or outside the supported bounds.");
        problem.setType(URI.create("urn:accountshield:problem:invalid-decision-investigation"));
        problem.setTitle("Decision investigation rejected");
        problem.setProperty("code", "INVALID_DECISION_INVESTIGATION");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DecisionTimelineNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(DecisionTimelineNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "The requested decision investigation is unavailable.");
        problem.setType(URI.create("urn:accountshield:problem:decision-investigation-not-found"));
        problem.setTitle("Decision investigation unavailable");
        problem.setProperty("code", "DECISION_INVESTIGATION_NOT_FOUND");
        problem.setProperty("retryable", false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Decision investigation is temporarily unavailable.");
        problem.setType(URI.create("urn:accountshield:problem:decision-investigation-unavailable"));
        problem.setTitle("Decision investigation unavailable");
        problem.setProperty("code", "DECISION_INVESTIGATION_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
