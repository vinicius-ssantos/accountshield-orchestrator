package io.github.viniciusssantos.accountshield.policy.internal.web;

import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PolicyDirectoryController.class)
public class PolicyDirectoryProblemHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The policy directory request is invalid.");
        problem.setType(URI.create("urn:accountshield:problem:invalid-policy-directory-search"));
        problem.setTitle("Policy directory search rejected");
        problem.setProperty("code", "INVALID_POLICY_DIRECTORY_SEARCH");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Policy directory search is temporarily unavailable.");
        problem.setType(URI.create("urn:accountshield:problem:policy-directory-search-unavailable"));
        problem.setTitle("Policy directory search unavailable");
        problem.setProperty("code", "POLICY_DIRECTORY_SEARCH_UNAVAILABLE");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
