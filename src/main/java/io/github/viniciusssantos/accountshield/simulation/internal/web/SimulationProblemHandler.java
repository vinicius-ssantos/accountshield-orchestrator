package io.github.viniciusssantos.accountshield.simulation.internal.web;

import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SimulationController.class)
class SimulationProblemHandler {

    private static final URI UNKNOWN_ALGORITHM_VERSION_TYPE =
            URI.create("urn:accountshield:problem:unknown-algorithm-version");

    @ExceptionHandler(UnknownAlgorithmVersionException.class)
    public ResponseEntity<ProblemDetail> unknownAlgorithmVersion(UnknownAlgorithmVersionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "No risk algorithm implementation is registered for version: " + ex.algorithmVersion() + ".");
        problem.setType(UNKNOWN_ALGORITHM_VERSION_TYPE);
        problem.setTitle("Unknown algorithm version");
        problem.setProperty("code", "UNKNOWN_ALGORITHM_VERSION");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
