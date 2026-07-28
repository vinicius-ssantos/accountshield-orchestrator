# AccountShield Java SDK

A typed Java client for AccountShield's protection, challenge, and recovery API, plus a webhook
signature verifier for consumers receiving signed AccountShield webhooks. Standalone Maven project
with **no dependency on any AccountShield server-internal package** -- see `pom.xml`'s comment for
why that's structurally guaranteed, not just a convention.

See `docs/adr/0037-java-client-sdk-and-demo.md` for the full design and scoping rationale, and
`../demo/` for a complete runnable consumer built on this SDK.

## Install

This module is not yet published to a public artifact repository. Build and install it into your
local Maven repository:

```bash
cd sdk
mvn install
```

Then depend on it:

```xml
<dependency>
    <groupId>io.github.vinicius-ssantos</groupId>
    <artifactId>accountshield-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Quick start: submit a protection decision

```java
import io.github.viniciusssantos.accountshieldsdk.AccountShieldClient;
import io.github.viniciusssantos.accountshieldsdk.model.*;
import java.net.URI;
import java.util.UUID;

AccountShieldClient client = AccountShieldClient.builder(URI.create("http://localhost:8080")).build();

ProtectionDecisionRequest request = ProtectionDecisionRequest
        .builder("alice@example.com", ProtectionEventType.LOGIN_ATTEMPT)
        .newDevice(true)
        .networkRiskLevel(NetworkRiskLevel.MEDIUM)
        // Setting an idempotency key is what makes this specific call safe to retry -- see
        // RetryPolicy's javadoc and "Retries" below.
        .idempotencyKey("checkout-" + UUID.randomUUID())
        .build();

ProtectionDecisionResponse response = client.decideProtection(request);

switch (response.outcome()) {
    case ALLOW -> System.out.println("Proceed normally.");
    case REQUIRE_STEP_UP -> System.out.println("Challenge issued: " + response.challenge().challengeId());
    case START_RECOVERY -> System.out.println("Recovery authorization: " + response.recoveryAuthorizationId());
    case TEMPORARILY_BLOCK -> System.out.println("Request blocked.");
}
```

## Handling a step-up challenge

```java
ChallengeVerificationResponse verification = client.verifyChallenge(
        response.challenge().challengeId(),
        new ChallengeVerificationRequest(userProvidedCode, ChallengePurpose.PROTECTION_STEP_UP,
                response.protectionRequestId()),
        null); // correlationId, or null to auto-generate one

if (verification.verified()) {
    System.out.println("Step-up succeeded.");
} else {
    System.out.println("Step-up failed, " + verification.remainingAttempts() + " attempts remaining.");
}
```

**Never retried automatically**: each `verifyChallenge` call consumes one of the challenge's own
attempts server-side, so retrying a timed-out call could exhaust a legitimate user's remaining
attempts for a request that may have already succeeded. If you need your own retry, you are
responsible for deciding it's safe given your specific failure mode.

## Handling recovery

```java
RecoveryResponse recovery = client.initiateRecovery(response.recoveryAuthorizationId(), null);
System.out.println("Recovery " + recovery.recoveryId() + " status=" + recovery.status());

// After the recovery's identity challenge (issued separately) has been verified:
client.confirmRecoveryIdentity(recovery.recoveryId(), verifiedChallengeId, null);
client.completeRecovery(recovery.recoveryId(), null);
```

## Verifying an inbound webhook

```java
import io.github.viniciusssantos.accountshieldsdk.webhook.*;

WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(mySharedSecret);

WebhookVerificationResult result = verifier.verify(
        request.getHeader(WebhookSignatureVerifier.SIGNATURE_HEADER),
        request.getHeader(WebhookSignatureVerifier.TIMESTAMP_HEADER),
        request.getHeader(WebhookSignatureVerifier.DELIVERY_ID_HEADER),
        rawRequestBody); // the exact raw bytes/string, before any JSON parsing

if (result.accepted()) {
    // process the event
} else {
    // result.outcome() is STALE_TIMESTAMP, INVALID_SIGNATURE, or DUPLICATE_DELIVERY
}
```

One `WebhookSignatureVerifier` instance should be reused across requests (it holds the bounded
replay-dedup cache); do not construct a new one per request.

## Retries

`AccountShieldClient` retries an operation **only when it has been told that operation is safe**:

| Operation | Retried by default? | Why |
|---|---|---|
| `decideProtection` with an `idempotencyKey` set | Yes | The server's idempotency store guarantees a retried call with the same key returns the original decision. |
| `decideProtection` with no `idempotencyKey` | No | The SDK cannot know whether the original attempt's side effects already landed. |
| `initiateRecovery` | Yes | Re-initiating with the same authorization ID returns the existing flow rather than creating a second one. |
| `verifyChallenge` | Never | Each attempt consumes the challenge's own attempt budget. |
| `confirmRecoveryIdentity`, `completeRecovery` | No (conservative default) | This SDK does not assert their idempotency semantics. |

Retries only ever happen for network failures or `429`/`502`/`503`/`504` responses -- never for a
`4xx` the server returned deliberately (other than `429`). Configure via
`AccountShieldClient.builder(...).retryPolicy(new RetryPolicy(maxAttempts, baseDelay, maxDelay))`.

## Error handling

Every non-2xx response the server describes with a Problem Details body (RFC 9457) is surfaced as
a typed `AccountShieldApiException`:

```java
try {
    client.decideProtection(request);
} catch (AccountShieldApiException e) {
    System.out.println(e.httpStatus() + " " + e.problem().code() + ": " + e.problem().detail());
}
```

Network failures and undescribed error responses throw `AccountShieldClientException` instead.

## Configuration

```java
AccountShieldClient client = AccountShieldClient.builder(URI.create("http://localhost:8080"))
        .connectTimeout(Duration.ofSeconds(3))
        .requestTimeout(Duration.ofSeconds(8))
        .retryPolicy(RetryPolicy.defaultPolicy()) // or RetryPolicy.noRetries()
        .traceparentSupplier(() -> currentTraceparentHeaderOrNull())
        .build();
```
