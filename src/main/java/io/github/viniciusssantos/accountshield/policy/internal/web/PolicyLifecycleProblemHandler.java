package io.github.viniciusssantos.accountshield.policy.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.policy.DuplicatePolicyVersionException;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PendingPolicyVersionExistsException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisFailedException;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.SelfApprovalNotAllowedException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PolicyLifecycleController.class)
class PolicyLifecycleProblemHandler {

    private static final URI ILLEGAL_TRANSITION_TYPE =
            URI.create("urn:accountshield:problem:illegal-policy-transition");
    private static final URI CONFLICT_TYPE =
            URI.create("urn:accountshield:problem:policy-conflict");
    private static final URI NOT_FOUND_TYPE =
            URI.create("urn:accountshield:problem:policy-version-not-found");
    private static final URI STEP_UP_INVALID_TYPE =
            URI.create("urn:accountshield:problem:invalid-challenge-state");
    private static final URI STEP_UP_REJECTED_TYPE =
            URI.create("urn:accountshield:problem:challenge-use-rejected");
    private static final URI ANALYSIS_FAILED_TYPE =
            URI.create("urn:accountshield:problem:policy-analysis-failed");
    private static final URI SELF_APPROVAL_TYPE =
            URI.create("urn:accountshield:problem:self-approval-not-allowed");

    @ExceptionHandler(IllegalPolicyTransitionException.class)
    public ResponseEntity<ProblemDetail> illegalTransition(IllegalPolicyTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Policy " + ex.policyKey() + ":" + ex.version()
                        + " cannot move from " + ex.fromStatus() + " to " + ex.toStatus() + ".");
        problem.setType(ILLEGAL_TRANSITION_TYPE);
        problem.setTitle("Illegal policy transition");
        problem.setProperty("code", "ILLEGAL_TRANSITION");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DuplicatePolicyVersionException.class)
    public ResponseEntity<ProblemDetail> duplicateVersion(DuplicatePolicyVersionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Policy " + ex.policyKey() + ":" + ex.version() + " already exists.");
        problem.setType(CONFLICT_TYPE);
        problem.setTitle("Policy conflict");
        problem.setProperty("code", "POLICY_VERSION_ALREADY_EXISTS");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PendingPolicyVersionExistsException.class)
    public ResponseEntity<ProblemDetail> pendingVersionExists(PendingPolicyVersionExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Policy key " + ex.policyKey() + " already has a draft or validated version pending.");
        problem.setType(CONFLICT_TYPE);
        problem.setTitle("Policy conflict");
        problem.setProperty("code", "PENDING_POLICY_VERSION_EXISTS");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PolicyVersionNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(PolicyVersionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Policy " + ex.policyKey() + ":" + ex.version() + " was not found.");
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Policy version not found");
        problem.setProperty("code", "POLICY_VERSION_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(PolicyAnalysisFailedException.class)
    public ResponseEntity<ProblemDetail> analysisFailed(PolicyAnalysisFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Policy " + ex.policyKey() + ":" + ex.version()
                        + " failed semantic analysis and cannot be validated.");
        problem.setType(ANALYSIS_FAILED_TYPE);
        problem.setTitle("Policy analysis failed");
        problem.setProperty("code", "POLICY_ANALYSIS_FAILED");
        problem.setProperty("analyzerVersion", ex.result().analyzerVersion());
        problem.setProperty("diagnostics", List.copyOf(ex.result().diagnostics()));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(SelfApprovalNotAllowedException.class)
    public ResponseEntity<ProblemDetail> selfApprovalNotAllowed(SelfApprovalNotAllowedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Actor " + ex.actor() + " authored policy " + ex.policyKey() + ":" + ex.version()
                        + " and cannot approve it.");
        problem.setType(SELF_APPROVAL_TYPE);
        problem.setTitle("Self-approval not allowed");
        problem.setProperty("code", "SELF_APPROVAL_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InvalidChallengeStateException.class)
    public ResponseEntity<ProblemDetail> stepUpInvalidState(InvalidChallengeStateException ex) {
        HttpStatus status = ex.currentStatus() == ChallengeStatus.EXPIRED
                ? HttpStatus.GONE
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                "The step-up challenge is in state " + ex.currentStatus() + " and cannot authorize this action.");
        problem.setType(STEP_UP_INVALID_TYPE);
        problem.setTitle("Invalid step-up challenge state");
        problem.setProperty("code", "INVALID_CHALLENGE_STATE");
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(ChallengeUseRejectedException.class)
    public ResponseEntity<ProblemDetail> stepUpRejected(ChallengeUseRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The step-up challenge cannot authorize this action.");
        problem.setType(STEP_UP_REJECTED_TYPE);
        problem.setTitle("Step-up challenge rejected");
        problem.setProperty("code", "CHALLENGE_USE_REJECTED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
