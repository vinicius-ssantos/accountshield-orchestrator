package io.github.viniciusssantos.accountshieldsdk;

/** Thrown for network/IO failures, or a non-2xx response the server did not describe as a Problem Details body. */
public final class AccountShieldClientException extends RuntimeException {

    public AccountShieldClientException(String message) {
        super(message);
    }

    public AccountShieldClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
