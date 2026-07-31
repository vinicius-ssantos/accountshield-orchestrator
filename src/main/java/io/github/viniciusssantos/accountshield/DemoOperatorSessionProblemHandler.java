package io.github.viniciusssantos.accountshield;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DemoOperatorSessionController.class)
class DemoOperatorSessionProblemHandler {

    private static final URI INVALID_CREDENTIALS_TYPE = URI.create("urn:accountshield:problem:invalid-credentials");
    private static final URI INVALID_REQUEST_TYPE =
            URI.create("urn:accountshield:problem:invalid-session-token-request");

    // Returned for both a wrong password AND an unknown username, with identical status, type,
    // title, code, and detail -- DemoOperatorCredentialVerifier already guarantees uniform
    // comparison cost for both cases, and this handler must not reintroduce a distinguishable
    // response shape on top of that.
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> invalidCredentials(InvalidCredentialsException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "The supplied username or password is not valid.");
        problem.setType(INVALID_CREDENTIALS_TYPE);
        problem.setTitle("Invalid credentials");
        problem.setProperty("code", "INVALID_CREDENTIALS");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The session token request is malformed.");
        problem.setType(INVALID_REQUEST_TYPE);
        problem.setTitle("Session token request rejected");
        problem.setProperty("code", "INVALID_SESSION_TOKEN_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }
}
