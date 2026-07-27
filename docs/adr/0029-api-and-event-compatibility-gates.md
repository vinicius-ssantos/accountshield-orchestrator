# ADR 0029: OpenAPI and AsyncAPI backward-compatibility gates

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #52 asked to prevent accidental breaking changes to HTTP APIs and integration events: detect
removed fields, incompatible type changes, new required fields, enum removals, and endpoint/event
removals; compare PR contracts against the latest released baseline; define a versioning policy;
add consumer contract tests; publish generated contracts as release artifacts. `docs/roadmap.md`'s
Gate 5 (this issue's own gate) states the actual exit bar precisely: **"CI detects incompatible
API/event changes"** -- narrower than the issue's full scope list, and the most authoritative
proportionality signal available, so this ADR treats it as the primary bar and the rest as
supporting infrastructure sized to match.

Two hard constraints shaped the design, discovered by direct investigation rather than assumption:

- **Zero git tags or GitHub releases exist in this repository** (`git tag -l` and `gh release
  list` both empty). "Baseline tied to a tagged release" therefore has nothing to tie to yet.
- **This environment cannot run the application** (no Maven installation, and the available local
  JDK does not match the project's toolchain) -- `mvn --batch-mode --no-transfer-progress verify`
  in CI is, as with every prior issue this session, the only way to actually execute the app and
  observe its real generated OpenAPI document.

Investigation also found: `springdoc-openapi-starter-webmvc-ui:2.8.0` already exposes a live
`/v3/api-docs` endpoint (no static-generation Maven plugin exists yet); the outbox module already
wraps exactly six integration event types in one shared-version envelope
(`outbox.IntegrationEventEnvelope`, `outbox.IntegrationEventSchema.CURRENT_VERSION =
"integration-event-1.0"`); four of those six have their `accountReference` field stripped and
replaced with a pseudonym before serialization (`OutboxEventRecorder.pseudonymizedPayload`) -- so a
contract test must document and check the *wire* shape, not the raw Java record; and no JSON
Schema, AsyncAPI document, or SDK of any kind exists anywhere in this codebase today.

## Decision

### Hand-rolled structural comparators, not a third-party diff library

`OpenApiSchemaCompatibilityChecker` and `EventPayloadShapeChecker` (both `src/test/java`, since
neither is called by the running application) operate on the same plain `Map`/`List`/scalar trees
`ObjectMapper.readValue(json, Map.class)` already produces elsewhere in this codebase (e.g.
`JdbcDecisionTraceQuery.parseContext`), rather than adopting a library such as
`org.openapitools.openapidiff:openapi-diff-core`. This is a deliberate departure from "use the
established library for the job" purely because of *this session's specific constraint*: with no
way to run `mvn` locally, a new dependency's transitive-version compatibility with this project's
Spring Boot 4.1 / Java 25 stack (springdoc 2.8.0 already pulls its own `swagger-core`; a diff
library would pull a second, possibly conflicting one) could only be discovered through a CI
failure, and a failure there could be a confusing runtime `NoSuchMethodError` rather than a clear
compile error. A hand-rolled comparator has no such risk, and -- critically -- its correctness does
not depend on a live app at all: `OpenApiSchemaCompatibilityCheckerTest` and
`EventPayloadShapeCheckerTest` prove it against hand-built synthetic before/after trees, fully
independent of what springdoc's real output turns out to look like. Both comparators are narrow by
design, checking only endpoint/method existence, schema `properties`/`type`/`required`/`enum` (with
bounded-depth `$ref` resolution for OpenAPI; plain field-presence/JSON-kind diffing for event data,
since a data fixture has no schema keywords to read) -- exactly the categories issue #52 names, not
a general-purpose diff.

### Enum-value removal for event payloads: a hardcoded constant-set test, not schema tooling

Four of the six event payloads carry a plain `String` field representing a domain enum's `.name()`
(`outcome`, `finalStatus`, `eventType`, `classification`) rather than the enum type itself on the
wire -- a raw JSON string does not self-document its allowed value set the way an OpenAPI schema's
`enum` array does. Rather than inventing a schema format for event data just to express "these are
the only legal strings," `DomainEnumCompatibilityTest` hardcodes the current constant set for every
domain enum serialized into a public API response or event payload
(`ChallengeType`/`ChallengeStatus`/`ProtectionOutcome`/`RecoveryEventType`/
`RecoveryRiskClassification`/`RiskBand`) and asserts none has silently disappeared; new constants
are always allowed. This is the same category of check `OpenApiSchemaCompatibilityChecker`'s
`enum` handling performs for schema-documented enums, just applied where no schema exists to walk.

### Baseline bootstrap: this PR ships the mechanism, not yet an active gate

With zero prior tags/releases and no way to run the app locally to hand-produce an accurate
baseline file, `OpenApiCompatibilityTest` and `IntegrationEventContractTest` self-bootstrap: if
their respective checked-in baseline file
(`src/test/resources/contracts/openapi-baseline.json`, `src/test/resources/contracts/events/
<eventType>.json`) is absent, the test captures the current spec/fixture shape, writes it to that
path, and passes. **This PR does not commit those baseline files** -- doing so without ever having
run the real app would mean guessing springdoc's exact generated JSON (schema naming, `$ref`
structure, nullability conventions) by hand, and an inaccurate guess would make the very first
comparison fail for reasons that are transcription errors, not real incompatibilities. Instead:
this PR's own CI run exercises the bootstrap path (proving the mechanism produces valid output and
the artifact-upload step works), and a small follow-up commit -- taking the `contracts` build
artifact CI produces and committing it as the baseline -- activates the gate for every subsequent
PR. From that point on, any incompatible change fails the relevant test unless the baseline file
itself is deliberately updated in the same PR, which is a visible, reviewable diff -- the practical
mechanism behind "compare PR contracts against the latest released baseline" until a real tagged
release exists to fetch from instead (see Revisit criteria).

### Versioning policy

- **HTTP API**: a breaking change (per `OpenApiSchemaCompatibilityChecker`'s categories) requires
  bumping `OpenApiConfiguration`'s `Info.version` major component in the same PR that updates the
  baseline file. Additive changes (new endpoint, new optional field, new enum value) do not require
  a bump.
- **Integration events**: there is exactly one shared envelope schema version
  (`IntegrationEventSchema.CURRENT_VERSION`) across all six event types, not one per event type --
  consumers already key off this single field. A breaking change to *any one* event payload
  therefore requires bumping this one shared constant, in the same PR that updates that event
  type's baseline fixture file.
- **Domain enums serialized on the wire**: removing or renaming a constant already covered by
  `DomainEnumCompatibilityTest` is a breaking change requiring the same major-version bump as the
  API or event surface that serializes it; adding a constant is additive.

### Consumer contract tests

`DemoWebhookReceiverConsumerContractTest` (new) posts a real, documented event-envelope fixture --
not a placeholder body -- to `DemoWebhookReceiverController` (this codebase's existing reference
webhook consumer, ADR 0026) and then deserializes the exact same raw body back into the real
`IntegrationEventEnvelope` type, proving both delivery acceptance and shape round-tripping. There is
no published SDK package anywhere in this codebase (confirmed by search); "consumer contract tests
for SDK" is satisfied proportionally by `IntegrationEventContractTest` and
`IntegrationEventFixtures` themselves deserializing cleanly back into the real domain types used to
build them, rather than inventing a placeholder SDK package with no other purpose.

### Publishing contracts as artifacts: CI build artifact now, tag-triggered release upload deferred

`ci.yml`'s `verify` job now always (`if: always()`) uploads `target/contracts/**` (the live OpenAPI
document `OpenApiCompatibilityTest` writes, plus a copy of the hand-authored `docs/contracts/
asyncapi.yaml`) as a GitHub Actions build artifact, exercised on every push and PR. A
tag-triggered workflow that uploads the same contracts to an actual GitHub Release is explicitly
**not** added in this PR: with zero tags ever pushed, such a workflow could not be exercised or
verified at all right now, and this session's established discipline is to avoid shipping
meaningfully untestable CI infrastructure. The build-artifact upload already exercises the entire
contract-generation pipeline on every run, which is the proportional bar the roadmap's Gate 5 exit
criterion actually asks for.

## Alternatives considered

- **`org.openapitools.openapidiff:openapi-diff-core` (or a GitHub Actions marketplace action like
  `oasdiff/oasdiff-action`)** -- rejected for the transitive-dependency and verifiability reasons
  above; a marketplace action would also be a second gate mechanism running outside this repo's
  established "CI = `mvn verify`" pattern used by every prior issue this session.
  A JSON-Schema-based validation library (e.g. `networknt/json-schema-validator`) for event
  payloads was considered and rejected for the same reason: a new, unverified dependency solving a
  problem the existing `ObjectMapper`/`Map` machinery already solves adequately for this issue's
  named categories.
- **Auto-committing the bootstrapped baseline from CI** -- rejected: pushing generated files back
  to a PR branch from a workflow needs elevated permissions and risks a confusing infinite-loop
  re-trigger; a small, explicit, human-reviewed follow-up commit is simpler and safer.
- **A tag-triggered GitHub Release upload workflow, added now** -- rejected as untestable
  infrastructure with zero tags to exercise it against; deferred with a stated revisit criterion.

## Consequences

### Positive

- CI now has a concrete, working mechanism to detect the exact breaking-change categories issue
  #52 names, for both HTTP endpoints and integration events, backed entirely by existing
  dependencies (zero new runtime or test libraries);
- the comparator's own correctness is proven by synthetic-fixture unit tests independent of ever
  running the real application, which matters given this environment cannot run Maven;
- a real, faithful (pseudonymization-aware) fixture per event type now exists and is reused by both
  the contract test and the new consumer contract test, rather than each inventing its own;
- the versioning policy gives a precise, checkable rule for both HTTP and event surfaces, including
  the important subtlety that events share one global schema version, not one per event type.

### Negative

- this PR's own CI run only exercises the bootstrap path, not a real comparison -- the gate is not
  actually *active* until a maintainer commits the generated baseline files in a follow-up PR;
- the hand-rolled comparators are narrower than a general-purpose OpenAPI-diff tool (no parameter,
  header, or response-status-code comparison, no `format`-level type refinement) -- sufficient for
  the categories this issue names, not a complete OpenAPI compatibility engine;
- `IntegrationEventFixtures`' pseudonymization/transform logic is a manual mirror of
  `OutboxEventRecorder.pseudonymizedPayload`, not an interception of the real publish path -- if
  that transform changes without updating this fixture code, the contract test could pass while
  silently testing a shape production no longer actually emits;
- a tag-triggered release-artifact-upload workflow does not exist yet.

## Guardrails

- `OpenApiSchemaCompatibilityCheckerTest` / `EventPayloadShapeCheckerTest`: synthetic-fixture unit
  tests proving each violation category (removed field, type change, new required field, enum
  removal, endpoint/method removal) is detected and that additive changes are not flagged;
- `OpenApiCompatibilityTest`: real `/v3/api-docs` fetched via `MockMvc` against the full Spring
  context, compared against the checked-in baseline (or bootstrapped if absent), and always written
  to the `target/contracts` build artifact;
- `IntegrationEventContractTest`: all six event types' fixtures diffed against their checked-in
  baselines (or bootstrapped if absent);
- `DomainEnumCompatibilityTest`: every domain enum serialized on the wire retains its full
  previously-published constant set;
- `DemoWebhookReceiverConsumerContractTest`: a real fixture is accepted by the reference consumer
  and round-trips through the real envelope type with the expected pseudonymization already
  applied.

## Revisit criteria

- when the first git tag / GitHub Release is actually cut: switch the baseline source from a
  checked-in file to fetching the previous release's published artifact, and add the tag-triggered
  release-upload workflow this ADR deferred;
- if a real, well-verified OpenAPI/AsyncAPI diff library becomes safe to adopt in this environment
  (e.g. once local Maven/JDK availability allows verifying it before merge), consider replacing the
  hand-rolled comparators -- they were chosen for this session's specific verifiability constraint,
  not because they are inherently superior to purpose-built tooling;
- if `OutboxEventRecorder`'s pseudonymization transform changes, update
  `IntegrationEventFixtures` in the same PR (noted here so it isn't missed).

## Links

- Issue #52
- `docs/roadmap.md` Gate 5 (the authoritative scope signal for this issue)
- [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md) (the pseudonymization
  scheme event fixtures must mirror)
- [ADR 0023](0023-outbox-claiming-backoff-and-dead-letters.md) (the versioned integration-event
  envelope this issue's event contracts describe)
- [ADR 0026](0026-signed-webhook-delivery-with-replay-protection.md) (the webhook delivery and
  reference consumer this issue's consumer contract test exercises)
- Tests: `OpenApiSchemaCompatibilityCheckerTest`, `EventPayloadShapeCheckerTest`,
  `OpenApiCompatibilityTest`, `IntegrationEventContractTest`, `DomainEnumCompatibilityTest`,
  `DemoWebhookReceiverConsumerContractTest`
