# Architecture decision records

Architecture decision records preserve the reasoning and constraints behind AccountShield's design. The architecture pages describe the current executable system; ADRs explain why important choices were made.

## Index

| ADR | Status | Decision | Related architecture/features |
| --- | --- | --- | --- |
| [0001](0001-start-as-a-modular-monolith.md) | Accepted | Start as a modular monolith and extract only with evidence | [Architecture baseline](../architecture/README.md) |
| [0002](0002-use-postgresql-as-source-of-truth.md) | Accepted | Use PostgreSQL as the correctness source of truth | [Persistence](../architecture/persistence.md) |
| [0003](0003-idempotency-via-caller-key-and-fingerprint.md) | Accepted | Identify logical retries through caller key and deterministic fingerprint | [Feature catalog](../features/README.md), issues #18 and #22 |
| [0004](0004-challenge-orchestration-via-simulated-providers.md) | Accepted | Model purpose-bound challenge orchestration with simulated providers | Challenge section in the [feature catalog](../features/README.md) |
| [0005](0005-recovery-flow-state-machine.md) | Accepted | Enforce an explicit risk-gated recovery state machine | [Recovery architecture](../architecture/recovery.md) |
| [0006](0006-deterministic-replay-and-shadow-evaluation.md) | Accepted | Keep replay and shadow evaluation deterministic and side-effect-free | Replay section in the [feature catalog](../features/README.md) |
| [0007](0007-policy-lifecycle-state-machine.md) | Accepted | Use immutable activated policy versions and controlled lifecycle transitions | Policy section in the [feature catalog](../features/README.md) |
| [0008](0008-in-memory-rate-limiting.md) | Accepted | Use in-memory rate limiting for the current single-instance baseline | [Architecture baseline](../architecture/README.md) |
| [0009](0009-outbox-relay-with-simulated-publisher.md) | Superseded by 0023 | Persist publication intent transactionally and use a simulated relay | Outbox section in the [feature catalog](../features/README.md) |
| [0010](0010-recovery-trust-boundaries.md) | Accepted | Use explicit `START_RECOVERY` and an immutable consumable recovery authorization; audit remains evidence | [Recovery](../architecture/recovery.md), [invariants](../architecture/invariants.md) |
| [0011](0011-jwt-resource-server-with-local-issuer.md) | Accepted | Authorize sensitive APIs with a JWT resource server backed by a local, per-boot key pair | Issue #19 |
| [0012](0012-pseudonymous-subject-tokens-for-integration-events.md) | Accepted | Pseudonymize account identifiers at the outbox integration boundary and add challenge-plan retention | Issue #32 |
| [0013](0013-risk-signal-provenance-envelope.md) | Accepted | Wrap risk signals in a provenance envelope with staleness rejection and confidence-based scoring | Issue #45 |
| [0014](0014-explicit-degradation-strategies-for-dependency-failures.md) | Accepted | Classify dependency failures with explicit degradation strategies; degrade challenge-provider failure to a recorded, safe `TEMPORARILY_BLOCK` | Issue #44 |
| [0015](0015-policy-threshold-analyzer.md) | Accepted | Add a deterministic policy-threshold analyzer as a real `validate()` gate, with versioned, auditable results | Issue #46 |
| [0016](0016-maker-checker-policy-approval.md) | Accepted | Add a maker-checker `APPROVED` stage with self-approval prevention before policy activation | Issue #33 |
| [0017](0017-client-context-and-policy-routing.md) | Accepted | Scope idempotency and rate limiting per client and add a `PolicyRoutingService` for client/event-aware policy selection | Issue #26 |
| [0018](0018-idempotency-claim-before-work.md) | Accepted | Claim idempotency before any side effect (insert-first, `ON CONFLICT DO NOTHING`) instead of recording after work; remove implementation leakage | Issue #22 |
| [0019](0019-deterministic-replay-algorithm-registry.md) | Accepted | Replay re-runs the historical risk algorithm via a self-registering `RiskAlgorithmRegistry` and reports field-level mismatches | Issue #21 |
| [0020](0020-replay-provenance-canonical-hash-and-catalog-versions.md) | Accepted | Replay compares a recomputed canonical input hash and validates reason-catalog/decision-engine versions | Issue #43 |
| [0021](0021-historical-policy-impact-analysis.md) | Accepted | Evaluate a candidate policy version against recent historical traces, reporting a transition matrix, segment breakdowns, and a configurable divergence threshold | Issue #35 |
| [0022](0022-deterministic-canary-rollout.md) | Accepted | Split traffic between a stable and candidate policy version using a deterministic cohort hash, recording selection provenance and supporting immediate rollback | Issue #34 |
| [0023](0023-outbox-claiming-backoff-and-dead-letters.md) | Accepted | Explicit outbox status machine, atomic `FOR UPDATE SKIP LOCKED` claiming, bounded backoff with jitter, visible dead letters, operator requeue, and a versioned integration-event envelope | Issue #23 |
| [0024](0024-database-least-privilege-and-integrity.md) | Accepted | Add restricted runtime/read-only database roles, close three referential-integrity gaps (deliberately leaving `recovery_authorization.decision_id` unconstrained per ADR 0010), and add the remaining retention job and metrics | Issue #25 |
| [0025](0025-envelope-encryption-key-rotation-and-crypto-shredding.md) | Accepted | Envelope-encrypt `protection_request.account_reference` with a per-subject key wrapped by a versioned KEK, rotate keys via a bounded rewrap job, and support crypto-shredding (deliberately scoped to `protection_request`; `decision_trace` and other tables deferred) | Issue #49 |
| [0026](0026-signed-webhook-delivery-with-replay-protection.md) | Accepted | Deliver signed, replay-protected webhooks through a new `OutboxEventPublisher` backed by the existing outbox retry/backoff/dead-letter loop, with per-subscription secrets and an in-process demo receiver (`audit.integrity.failed` deferred) | Issue #47 |
| [0027](0027-tamper-evident-audit-hash-chaining.md) | Accepted | Chain `audit.decision_trace` rows by content hash (application-assigned sequence, advisory-lock-serialized append), with bounded-range verification, a forward-only checkpointed integrity job, and operator diagnostics; completes ADR 0026's deferred `audit.integrity.failed` | Issue #40 |
| [0028](0028-signed-redacted-decision-evidence-bundles.md) | Accepted | Compose the audit trace, replay, and audit-chain proof into one canonical-JSON evidence bundle, redact the raw account reference via the existing pseudonymization scheme, sign it with a new per-boot RSA signer, and audit every export (who, why) | Issue #42 |
| [0029](0029-api-and-event-compatibility-gates.md) | Accepted | Detect breaking OpenAPI/event changes via hand-rolled structural comparators (no new dependency), a versioning policy, a self-bootstrapping baseline (no tagged release exists yet), consumer contract tests, and a build-artifact contract upload | Issue #52 |
| [0030](0030-transaction-aware-observability-and-tracing.md) | Accepted | Defer success metrics/logs to `@TransactionalEventListener(AFTER_COMMIT)` (except two deliberately-synchronous denied-attempt security logs), add a real duration `Timer` with explicit SLO buckets, and wire Micrometer Tracing + OTLP + `@Observed` spans with Jaeger in Compose | Issue #24 |
| [0031](0031-ci-and-software-supply-chain-security.md) | Accepted | Add JaCoCo/CycloneDX (report-only, no baseline yet), a hard-gating dependency review, advisory Trivy/Gitleaks scans, CodeQL, Dependabot, a real container smoke test, and pinned Compose image versions; defer SpotBugs/Checkstyle/coverage thresholds until a real baseline exists | Issue #27 |
| [0032](0032-resilience-and-concurrency-fault-injection.md) | Accepted | Close the 4 real gaps among issue #39's 8 named fault-injection scenarios (outbox reclaim, challenge concurrency, recovery clock boundary, Toxiproxy-based DB latency/interruption), cross-referencing the 4 already covered by prior tests; add a `@Tag("resilience")`/nightly-workflow split | Issue #39 |
| [0033](0033-property-based-tests-and-api-fuzzing.md) | Accepted | Add jqwik property tests for risk-score range, idempotency equivalence/conflict, and challenge terminal-state monotonicity, plus a curated malformed-request fuzz test; bounded tries by default, deeper nightly fuzzing via `junit-platform.properties` override | Issue #53 |
| [0034](0034-adversarial-account-takeover-scenario-lab.md) | Accepted | Implement 5 of 9 named adversarial scenarios (credential stuffing, impossible travel, device takeover, MFA fatigue, recovery abuse) with hand-verified expected scores against the real risk formula and policy thresholds, each feeding a real signed evidence bundle and a shared Markdown decision-timeline report as a CI artifact; defer SIM swap, session replay, insider misuse, and password spraying for named signal-model gaps | Issue #54 |
| [0035](0035-reproducible-capacity-benchmark.md) | Accepted | Measure all 8 named capacity dimensions (decision throughput/latency, policy evaluation, persistence, outbox publish, replay, database growth/index impact, connection-pool saturation, audit/hash-chain overhead) against the real Spring context and Testcontainers Postgres; report-only (no hardcoded numbers), `@Tag("benchmark")`/nightly split with a small untagged default-gate smoke test, connection-pool saturation as the required measured bottleneck | Issue #50 |
| [0036](0036-executable-backup-restore-disaster-recovery-drill.md) | Accepted | Executable `pg_dump`/`psql` backup-and-restore drill against a second, freshly-started Testcontainers Postgres and an independently-bootstrapped second Spring context; validates migrations, audit-chain integrity, active-policy uniqueness, and outbox republish-prevention post-restore, demonstrates RPO via an explicit data-loss boundary, measures RTO by phase; surfaced a real role/grant-restoration gap (ADR 0024) and documents KEK secret recovery considerations (ADR 0025) | Issue #51 |
| [0037](0037-java-client-sdk-and-demo.md) | Accepted | Standalone `accountshield-sdk` Maven module (no reactor/parent relationship to the server, structurally no server-package dependency) with typed protection/challenge/recovery clients, per-operation-explicit safe retries, and an independent webhook-signature/replay verifier; contract-verified via a live-instance server-side test (no static OpenAPI baseline exists to generate against); a CLI `accountshield-demo` module demonstrates all three outcomes plus webhook verification, wired into CI's end-to-end check and an opt-in Compose profile | Issue #55 |
| [0038](0038-scenario-cli.md) | Accepted | Standalone `accountshield-cli` Maven module (picocli, built entirely on `accountshield-sdk`) with `scenario list/run/report` (reusing ADR 0034's exact hand-verified scenario math, proven end to end by a real subprocess test), `policy lint/diff` (diff adapted to the real policy-impact API shape, no literal "stable version" argument), and `evidence verify` (byte-for-byte pass-through, not a risky local hash/signature reimplementation); documented exit-code and JSON-stability contract; executable JAR only, native binaries deferred to a future release pipeline | Issue #56 |
| [0039](0039-repository-governance-and-reproducible-release.md) | Accepted | Closed 23 issues with real merged work that GitHub never auto-closed (bare `#N` references, not `Closes #N`); rewrote README's badly stale delivery-status section; added PR/issue templates, CODEOWNERS, Conventional Commits documentation, a generated CHANGELOG.md, and a tag-triggered GHCR release workflow; real demo package (curl walkthrough, Postman collection, seed script, interview script); branch protection and the actual `v1.0.0` tag deliberately deferred to explicit owner confirmation as externally-visible, hard-to-reverse actions | Issue #28 |
| [0040](0040-privacy-minimized-decision-investigation-read-api.md) | Accepted | Persist bounded correlation metadata and expose an authorized, privacy-minimized decision-search/read model with body-based filters, keyset pagination, `no-store`, and no raw audit projection | Issues #69 and #134 |
| [0041](0041-privacy-minimized-decision-investigation-timeline.md) | Accepted | Aggregate audit, challenge, recovery and payload-free outbox projections through module-owned read ports into one authorized, body-based, deterministic and explicitly partial decision timeline | Issues #70 and #171 |
| [0042](0042-privacy-minimized-recovery-investigation-read-api.md) | Accepted | Expose an authorized, privacy-minimized recovery queue search and detail read model owned entirely by the `recovery` module, with body-based filters, keyset pagination, masked cross-module references, and explicit challenge-section availability | Issues #71 and #174 |

## ADR lifecycle

Use the following statuses:

- **Proposed:** decision is under review and must not be treated as an executable guarantee;
- **Accepted:** decision constrains implementation and future changes;
- **Superseded:** another ADR replaces the decision; both records remain available;
- **Deprecated:** retained for history but no longer recommended;
- **Rejected:** considered and explicitly not adopted.

## When a new ADR is required

Create or supersede an ADR when a change:

- changes module boundaries or distribution strategy;
- changes the source of truth or transactional authority;
- introduces or replaces an authorization mechanism;
- changes idempotency, retry, concurrency, or delivery guarantees;
- establishes a long-lived public API/event versioning policy;
- adopts a new cryptographic or data-retention strategy;
- accepts a significant operational trade-off;
- reverses an existing accepted decision.

A small refactor, library upgrade, or implementation detail normally does not require an ADR unless it changes one of these constraints.

## Required ADR structure

Each ADR should contain:

1. title, status, date, and update/supersession metadata;
2. context and problem;
3. decision;
4. alternatives considered;
5. positive and negative consequences;
6. executable guardrails;
7. migration/compatibility implications;
8. revisit criteria;
9. links to issues, architecture pages, migrations, and tests.

## Consistency rule

After an ADR is accepted and implemented:

- add it to this index;
- update the feature catalog status;
- update the relevant architecture and invariant documents;
- add or update automated tests proving the guardrails;
- ensure README claims do not exceed the delivered implementation.
