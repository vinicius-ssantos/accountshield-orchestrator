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

@RestControllerAdvice(assignableTypes = PolicyInvestigationController.class)
public class PolicyInvestigationProblemHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The policy investigation request is invalid.");
        problem.setType(URI.create("urn:accountshield:problem:invalid-policy-investigation"));
        problem.setTitle("Policy investigation rejected");
        problem.setProperty("code", "INVALID_POLICY_INVESTIGATION");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(PolicyInvestigationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(PolicyInvestigationNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "The requested policy investigation was not found.");
        problem.setType(URI.create("urn:accountshield:problem:policy-investigation-not-found"));
        problem.setTitle("Policy investigation not found");
        problem.setProperty("code", "POLICY_INVESTIGATION_NOT_FOUND");
        problem.setProperty("retryable", false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Policy investigation is temporarily unavailable.");
        problem.setType(URI.create("urn:accountshield:problem:policy-investigation-unavailable"));
        problem.setTitle("Policy investigation unavailable");
        problem.setProperty("code", "POLICY_INVESTIGATION_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
