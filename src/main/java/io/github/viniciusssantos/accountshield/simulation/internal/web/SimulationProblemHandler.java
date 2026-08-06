package io.github.viniciusssantos.accountshield.simulation.internal.web;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {SimulationController.class, PolicyImpactController.class})
class SimulationProblemHandler {

    private static final URI UNKNOWN_ALGORITHM_VERSION_TYPE =
            URI.create("urn:accountshield:problem:unknown-algorithm-version");
    private static final URI CANDIDATE_POLICY_VERSION_UNAVAILABLE_TYPE =
            URI.create("urn:accountshield:problem:candidate-policy-version-unavailable");

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

    @ExceptionHandler(ActivePolicyUnavailableException.class)
    public ResponseEntity<ProblemDetail> candidatePolicyVersionUnavailable(ActivePolicyUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "The requested policy key or candidate version could not be found.");
        problem.setType(CANDIDATE_POLICY_VERSION_UNAVAILABLE_TYPE);
        problem.setTitle("Candidate policy version unavailable");
        problem.setProperty("code", "CANDIDATE_POLICY_VERSION_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
