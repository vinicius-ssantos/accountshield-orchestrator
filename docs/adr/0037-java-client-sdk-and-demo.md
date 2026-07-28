# ADR 0037: Java client SDK and end-to-end integration demo

- Status: Accepted
- Date: 2026-07-28

## Context

Issue #55 asked for a typed Java client SDK (protection/recovery clients, idempotency-key support,
correlation-ID propagation, safe retries, configurable timeout, typed Problem Details, tracing
integration, contract-verified models) and a demo application (submits decisions, handles step-up/
recovery outcomes, receives signed webhooks, verifies signatures/replay protection, displays an
event timeline), runnable through Docker Compose with a CI-validated end-to-end flow.

roadmap.md's Gate 8 exit criterion is narrower than the issue's full deliverable list: "SDK uses
only public contracts with safe retries." The demo application is not named in the gate criterion
at all. This ADR treats the SDK as the load-bearing deliverable (built fully, to the letter of
every named acceptance item) and the demo as intentionally scoped down to a real, executable, but
minimal CLI consumer -- not a second web UI, which is issue #41's separate, larger scope (guarded
by "the frontend may remain read-only before Gate 2" and RBAC not existing yet for most privileged
surfaces).

## Decision

### A genuinely standalone Maven module, not a same-repo package trick

`sdk/` is its own Maven project (`sdk/pom.xml`) with **no `<parent>` and no reactor relationship**
to `accountshield-orchestrator`'s root `pom.xml`, which itself remains single-module (adding a
`<modules>` section to it was avoided as an unnecessarily large, repo-wide restructuring for what
is fundamentally an additive feature). This is what makes "SDK has no dependency on internal
server packages" a structurally enforced fact rather than a naming convention someone could
accidentally violate: there is no Maven relationship through which a server-internal class could
ever appear on the SDK's classpath. `demo/` is a second, equally standalone sibling that depends
only on `accountshield-sdk`.

The one deliberate, one-directional exception: `accountshield-orchestrator`'s own test suite adds a
**test-scope** dependency on `accountshield-sdk` (see root `pom.xml`'s comment) to prove the SDK
against a live instance of the real server (see "Contract verification" below). This does not
create a cycle and gives the SDK's own compiled classpath nothing new -- only the server's tests
gain a dependency, never the reverse.

### Hand-written models, verified against a live instance (not a static baseline)

There is no checked-in static OpenAPI document in this repository to generate or diff models
against -- ADR 0029's compatibility gate diffs the *live* `/v3/api-docs` endpoint against a
CI-artifact baseline, not a repository file. Rather than introduce an OpenAPI-codegen build-time
dependency disproportionate to this SDK's size, every request/response type is hand-written,
verified field-by-field against the real controller/DTO source at implementation time, and then
proven correct by `SdkContractVerificationTest` (on the server, using the SDK as a test-scope
dependency): it boots the real Spring context on a random port and drives it through
`AccountShieldClient`, asserting every typed field the SDK parses matches what the server actually
returned for `ALLOW`, `REQUIRE_STEP_UP` (plus a real challenge-verification round trip),
`START_RECOVERY` (plus a real recovery-initiation round trip), and a real validation-error Problem
Details response. This is the SDK's "contract-tested" guarantee -- a live round trip, not a
generated-and-hoped-consistent model.

### Authentication: bearer tokens, caught by CI, not anticipated at design time

Every endpoint the SDK calls except `/demo/webhook-receiver` sits behind this server's JWT
resource server (ADR 0011, `SecurityConfig`: `/api/v1/protection-decisions` and the
consumer-facing recovery endpoints require the `PROTECTION_CLIENT` role; `/api/v1/challenges/**`
requires any authenticated principal). This was **not accounted for in the initial implementation**
of this ADR -- `SdkContractVerificationTest` failed in CI with real `401 AUTHENTICATION_REQUIRED`
responses, exactly the kind of gap this project's "state assumptions and incomplete verification
clearly" standard exists to surface rather than hide. Fixed by:

- `AccountShieldClient.Builder.bearerToken(String)` / `.bearerTokenSupplier(Supplier<String>)`:
  attaches `Authorization: Bearer <token>` per request when configured, the same
  supplier-per-request pattern already used for `traceparent`.
- `SdkContractVerificationTest` mints a real `PROTECTION_CLIENT` token via the `LocalJwtKeys` bean
  directly (the same mechanism `SecurityIntegrationTest` already uses), not the profile-gated
  `/dev/tokens` HTTP endpoint, since a plain `@SpringBootTest` does not activate the `local`
  profile.
- `accountshield-demo` acquires a token itself: `ACCOUNTSHIELD_BEARER_TOKEN` if supplied, otherwise
  it self-mints one via `POST /dev/tokens` (dev/demo-only, `local`-profile-gated, permitted
  anonymously) -- not something a real external consumer would do, since they would obtain a token
  from whatever real identity provider issues them, which this demo has no equivalent of.
- `compose.yaml`'s `app` service and `ci.yml`'s smoke-test app container both now set
  `SPRING_PROFILES_ACTIVE=local`, enabling `/dev/tokens` for the demo run. Applied unconditionally
  to the `app` service (not only when the `demo` profile is used) since `compose.yaml` is already
  established, local-dev/demo-only tooling, not a hardened deployment descriptor -- the identical
  reasoning ADR 0024 used to justify not wiring the restricted database role into it.

### Retries: explicit safety, per operation, never inferred

`RetryPolicy` never infers whether an operation is safe from the HTTP method alone. Each
`AccountShieldClient` method states, at the call site, whether it is safe:

- `decideProtection` is safe **only when the caller sets `idempotencyKey`** (the server's
  idempotency store, ADR 0018, guarantees a retried call with the same key returns the original
  decision rather than duplicating a side effect) -- a request with no key is never retried, even
  on a network failure, since the SDK cannot know whether the original attempt's side effects
  already landed.
- `initiateRecovery` is always safe (re-initiating with the same authorization ID returns the
  existing flow server-side).
- `verifyChallenge` is **never** retried: each attempt consumes the challenge's own attempt budget:
  retrying a timed-out call could exhaust a legitimate user's remaining attempts for a request that
  may have already succeeded.
- `confirmRecoveryIdentity`/`completeRecovery` default to not-retried, conservatively, since this
  SDK does not assert their idempotency semantics.

Retries additionally only ever fire for network failures or `429`/`502`/`503`/`504` -- never a
`4xx` the server returned deliberately. This is the concrete mechanism behind "retries occur only
for safe operations."

### Webhook verification: an independent, faithful port of the server's exact algorithm

`WebhookSignatureVerifier` reimplements `webhook.internal.demo.DemoWebhookReceiverController`'s
exact logic (HMAC-SHA256 over `timestamp + "." + deliveryId + "." + rawBody`, hex-encoded, the same
three-step check order: timestamp freshness, then constant-time signature comparison, then
delivery-ID replay dedup) rather than depending on the server module to reuse it -- the SDK cannot
depend on the server at all, so this is a from-scratch, independently verified port, proven against
the exact header names and byte layout the server uses (`WebhookSignatureVerifierTest`).

### The demo: real and executable, deliberately not a second web UI

The demo (`accountshield-demo`) is a single runnable CLI-style Java program, not a Spring Boot web
application:

- submits three protection decisions using the exact signal combinations ADR 0034's scenario lab
  already hand-verified against the live scoring formula (`ALLOW`, `REQUIRE_STEP_UP` via impossible
  travel + new device, `START_RECOVERY` via a compromised-credential password-reset attempt) --
  real decisions against a real running server, not canned data;
- handles the step-up branch by calling `verifyChallenge` with a deliberately wrong code and
  printing the real response (demonstrating handling, not necessarily solving -- this system's
  challenge codes are simulated-provider-generated and not exposed for an external consumer to
  retrieve, ADR 0004);
- handles the recovery branch by calling `initiateRecovery` with the real returned authorization ID;
- demonstrates webhook verification and replay protection by constructing and signing a **sample**
  payload itself (via the SDK's own `WebhookSigner`, explicitly documented as mirroring the server's
  algorithm for this exact purpose) and feeding it through `WebhookSignatureVerifier` twice (second
  call proving replay rejection) -- **not** by registering a live webhook subscription against the
  running server. Webhook subscription management (`webhook.internal.web`) requires operator
  authentication (issue #19/#48 scope), which does not yet exist for an unauthenticated demo
  consumer to use; wiring a real subscription would require solving that authentication problem
  first, disproportionate to this issue's scope. This is an explicit, documented scope boundary,
  not an oversight.
- prints a simple timestamped event-timeline to stdout throughout, per the acceptance criterion.

### CI and Compose wiring

`ci.yml`'s `verify` job installs `accountshield-sdk` into the local Maven repo (its own tests run as
part of that `mvn install`) and packages `accountshield-demo` before running the main `mvn verify`
(the server's test suite depends on the SDK). The `docker` job -- on a fresh runner, so it rebuilds
both modules -- runs the real demo jar against the already-started, already-health-checked
smoke-test app container after the existing smoke test passes; a non-zero exit fails the job. This
is "CI validates the end-to-end flow" using a real running instance, not a mocked one.

`compose.yaml` gains a `demo` service under an opt-in `profiles: ["demo"]` (a one-shot client run,
not a standing service, should not start on a plain `docker compose up`), built from a new
`demo/Dockerfile` that reuses the root project's own Maven Wrapper (`-f sdk/pom.xml`, then
`-f demo/pom.xml`) rather than a Maven-bundled base image, since neither sibling module has its own
wrapper. Run with `docker compose --profile demo up --build demo`.

## Alternatives considered

- **A true multi-module Maven reactor** (root `pom.xml` gains `<modules>`, becomes `packaging=pom`)
  -- rejected: this issue is additive; restructuring the existing single-module build that every
  other workflow, the `Dockerfile`, and every prior ADR's tooling already assumes is a large,
  repo-wide risk for a feature that does not require it. The sibling-standalone-module approach
  gives an equally real, independently-buildable artifact with zero risk to existing tooling.
- **SDK code living in a new package within the same Maven module** -- rejected: this would make
  "no dependency on internal server packages" a naming convention enforced only by developer
  discipline, not a structural fact; a real client SDK should be independently distributable, which
  requires it to actually be a separate artifact.
- **OpenAPI-generator-driven model generation** -- rejected: this repository has no static OpenAPI
  baseline file to generate against (only a live-endpoint CI-artifact baseline, ADR 0029), and
  introducing a codegen Maven plugin is a disproportionately large addition for a codebase whose
  API surface (protection/challenge/recovery) is 3 endpoints. Hand-written models verified by a
  live-instance contract test are simpler and just as trustworthy.
- **Wiring a live, authenticated webhook subscription in the demo** -- rejected for now: requires
  solving operator authentication for an unauthenticated demo consumer first (issue #19/#48 scope).
  Revisit once that exists.
- **A second Spring Boot web application for the demo** -- rejected: disproportionate to a P3
  issue whose gate criterion only names the SDK explicitly; a CLI-style consumer demonstrates every
  acceptance item (submits decisions, handles outcomes, verifies webhooks, shows an event timeline)
  without building a second UI surface, which issue #41 already owns as a separate, larger effort.

## Consequences

### Positive

- the SDK is a real, independently buildable, independently distributable artifact with zero
  server dependency, provable by the absence of any Maven relationship, not just by inspection;
- every typed model is proven against a live server response, not merely hand-verified once at
  write time;
- the retry policy's safety reasoning is explicit and documented per operation, not inferred from
  HTTP method conventions that don't actually hold for this API (e.g. `POST /protection-decisions`
  is sometimes safe, sometimes not, depending on whether the caller supplied an idempotency key);
- the webhook verifier is independently useful to any real external consumer receiving
  AccountShield webhooks, not just a demo artifact.

### Negative

- the demo's webhook demonstration is self-contained (constructs and verifies its own sample
  payload) rather than proving a live, subscription-driven delivery end to end -- a real consumer
  wiring a live subscription still needs to solve webhook-subscription authentication themselves,
  which this demo does not demonstrate;
- two additional, independently-versioned Maven projects now exist in this repository with their
  own release/versioning lifecycle question (both currently `0.1.0-SNAPSHOT`, unpublished to any
  public repository) -- deferred, see Revisit criteria;
- `demo/Dockerfile`'s Compose profile was not run end-to-end in this environment during this PR's
  preparation (Docker was unavailable in this session at the time this feature was implemented,
  after being available for issues #50/#51 earlier in the same session) -- CI's `docker` job is the
  first real execution of both the demo jar directly (`java -jar`) and, separately, whether anyone
  has since exercised the Compose profile path specifically. Stated explicitly per this project's
  "state assumptions and incomplete verification clearly" standard.

## Guardrails

- `accountshield-sdk`: 14 unit tests (retry-policy decision table, webhook signature/replay/
  tamper/stale-timestamp cases, and `AccountShieldClient` HTTP behavior against a plain
  `com.sun.net.httpserver.HttpServer` fake -- no mocking framework) all passed locally before this
  PR.
- `SdkContractVerificationTest` (server-side, live-instance): written and compiled locally, but
  Docker/Testcontainers was unavailable in this environment at implementation time, so its first
  real execution was CI on the initial PR revision -- and it correctly caught the missing-
  authentication gap described above (3 errors, 1 failure, all `401`). That is exactly the kind of
  thing this test is for; fixed in the same PR (see "Authentication" above) and re-verified by CI
  on the corrected revision.
- `accountshield-demo`: compiles and packages standalone (`mvn package`, verified locally); the
  jar was run directly with no live server reachable, confirming clean retry behavior and graceful
  failure (including, after the auth fix, a clear message when token acquisition itself fails).
  The full live-server/Compose-profile path was not run in this environment for the same
  Docker-availability reason -- CI's `docker` job is its first real execution.
- No hardcoded server response data anywhere in the SDK; every model is a direct, minimal
  transcription of the real DTO field names and types.

## Revisit criteria

- If this SDK is ever meant for external, non-monorepo consumers, publish it to a real artifact
  repository (Maven Central or GitHub Packages) with a real versioning/release policy -- currently
  unpublished, local-install-only.
- Once operator authentication (issue #19/#48) is usable by an unauthenticated or service-account
  demo flow, revisit wiring a live webhook subscription into the demo instead of a self-signed
  sample payload.
- If a second SDK language or a broader external-partner integration program emerges, revisit
  OpenAPI-generator-driven model generation once there's enough surface area (and a static baseline
  file) to justify the added build complexity.

## Links

- Issue #55
- [ADR 0004](0004-challenge-orchestration-via-simulated-providers.md) (why the demo cannot retrieve
  a real challenge code)
- [ADR 0018](0018-idempotency-claim-before-work.md) (the idempotency guarantee `RetryPolicy` relies
  on for `decideProtection`)
- [ADR 0026](0026-signed-webhook-delivery-with-replay-protection.md) (the webhook signing scheme
  `WebhookSignatureVerifier` independently ports)
- [ADR 0029](0029-api-and-event-compatibility-gates.md) (why no static OpenAPI baseline exists to
  generate models from)
- [ADR 0034](0034-adversarial-account-takeover-scenario-lab.md) (the hand-verified scenario math
  the demo's three decisions reuse)
- Code: `sdk/`, `demo/`, `src/test/java/.../sdk/SdkContractVerificationTest.java`
