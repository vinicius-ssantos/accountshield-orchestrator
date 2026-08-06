package io.github.viniciusssantos.accountshield.investigation.internal.web;

import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DecisionReplayController.class)
public class DecisionReplayProblemHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The decision replay request is invalid.");
        problem.setType(URI.create("urn:accountshield:problem:invalid-decision-replay"));
        problem.setTitle("Decision replay rejected");
        problem.setProperty("code", "INVALID_DECISION_REPLAY");
        problem.setProperty("retryable", false);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DecisionReplayNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(DecisionReplayNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "The requested decision replay is unavailable.");
        problem.setType(URI.create("urn:accountshield:problem:decision-replay-not-found"));
        problem.setTitle("Decision replay not found");
        problem.setProperty("code", "DECISION_REPLAY_NOT_FOUND");
        problem.setProperty("retryable", false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler({DataAccessException.class, UnknownAlgorithmVersionException.class,
        PolicyVersionNotFoundException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Decision replay is temporarily unavailable.");
        problem.setType(URI.create("urn:accountshield:problem:decision-replay-unavailable"));
        problem.setTitle("Decision replay unavailable");
        problem.setProperty("code", "DECISION_REPLAY_UNAVAILABLE");
        problem.setProperty("retryable", exception instanceof DataAccessException);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
