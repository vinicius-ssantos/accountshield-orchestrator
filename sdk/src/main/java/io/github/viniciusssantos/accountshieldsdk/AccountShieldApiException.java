package io.github.viniciusssantos.accountshieldsdk;

import io.github.viniciusssantos.accountshieldsdk.model.ProblemDetails;

/** Thrown when the server returns a non-2xx response with a parsed {@link ProblemDetails} body. */
public final class AccountShieldApiException extends RuntimeException {

    private final int httpStatus;
    private final ProblemDetails problem;

    public AccountShieldApiException(int httpStatus, ProblemDetails problem) {
        super("AccountShield API error " + httpStatus
                + (problem.code() != null ? " (" + problem.code() + ")" : "")
                + (problem.detail() != null ? ": " + problem.detail() : ""));
        this.httpStatus = httpStatus;
        this.problem = problem;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ProblemDetails problem() {
        return problem;
    }
}
