# AccountShield feature catalog

- Baseline branch: `main`
- Baseline commit: `f1bc7eb54604773861344e1b785172780510a1d7`
- Updated: 2026-07-24

This catalog distinguishes executable behavior from planned hardening. A feature is **Implemented** only when its core path exists on `main` and is covered by automated verification. **Partial** means that the primary slice works but explicitly linked gaps remain.

## Status legend

| Status | Meaning |
| --- | --- |
| **Implemented** | Available on `main`, persisted or observable as documented, and covered by tests |
| **Partial** | Functional slice exists, but known correctness, security, operational, or governance work remains |
| **Planned** | Open issue exists; behavior must not be presented as delivered |
| **Deferred** | Intentionally outside the current portfolio release |

## Protection decision pipeline

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Protection decision orchestration | **Implemented** | Accepts an opaque account reference, explicit event type, risk signals, and idempotency key; returns one versioned outcome | [Protection architecture](../architecture/protection-decisions.md) |
| Deterministic risk scoring | **Implemented** | Normalized signals produce bounded score, band, ordered reason codes, and contributions | Risk module tests; provenance hardening: [#45](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/45) |
| Versioned policy evaluation | **Implemented** | Active immutable policy versions route standard and recovery-request events | ADR 0007; ADR 0015 |
| Explicit protection outcomes | **Implemented** | `ALLOW`, `REQUIRE_STEP_UP`, `START_RECOVERY`, and `TEMPORARILY_BLOCK` are distinct decisions | ADR 0010 |
| Decision idempotency | **Implemented** | Concurrent and sequential equivalent requests return the identical decision, scoped per client (ADR 0017); the claim happens before any side effect (ADR 0018), so losing racers are never left with a partial protection request; fingerprint conflicts are detected without leaking raw database exceptions | ADR 0018 |
| In-memory rate limiting | **Implemented** | Bounded sliding-window limits are scoped per client and account reference in the current single-instance demo | ADR 0008; ADR 0017 |
| Fail-safe dependency degradation | **Implemented** | Active policy, risk-signal staleness, and challenge-provider failures each have an explicit, classified strategy; challenge-provider failure degrades to a recorded, safe `TEMPORARILY_BLOCK` | ADR 0014 |

## Policy lifecycle and governance

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Draft, activation, retirement, immutable versions | **Implemented** | Policy lifecycle is persisted and only one active version is permitted by the current model | ADR 0007 |
| Recovery-specific policy threshold | **Implemented** | Versioned `recoveryMaxScore` produces `START_RECOVERY` for recovery-request events | ADR 0010 |
| Static policy analysis | **Partial** | Deterministic diagnostics over the numeric-threshold model (missing/out-of-range/shadowed thresholds) gate the `validate()` transition; rule/condition/signal-reference diagnostics require a future policy DSL | ADR 0015 |
| Maker-checker approval | **Partial** | Author/validator/approver actor identity, self-approval prevention, and an `APPROVED` gate before activation are implemented; rollback-to-a-retired-version and two-person/critical-class approval are not | ADR 0016 |
| Canary rollout and rollback | **Partial** | A candidate policy version can receive a deterministic, monotonically-expanding percentage of live traffic via cohort hashing; every decision records cohort/selection provenance; rollback is immediate and step-up-free. Scheduled effective-period auto-expiry and fully automatic metric-triggered rollback are not implemented (ADR 0022) | ADR 0022; [#34](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/34) |
| Historical policy impact reports | **Partial** | A candidate policy version can be evaluated against recent historical traces, producing a full ALLOW/STEP_UP/BLOCK/RECOVERY transition matrix, event-type and risk-band segment breakdowns, a redacted divergent-decision list, and a configurable divergence threshold flag; automatically hard-blocking policy approval on that flag is not yet wired into the maker-checker flow (ADR 0021) | ADR 0021; [#35](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/35) |
| Client/event policy routing | **Partial** | `PolicyRoutingService` resolves a policy key per client and protection event type; activation isolation follows from distinct policy keys per client. Cross-client replay/recovery access-control enforcement is not implemented | ADR 0017 |

## Audit, replay, and evidence

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Append-only decision trace | **Implemented** | Persists normalized retained context, risk details, policy and algorithm versions, outcome, timestamps, and correlation IDs | ADR 0002; [Persistence](../architecture/persistence.md) |
| Audit as evidence, not recovery authority | **Implemented** | Recovery initiation uses `RecoveryAuthorization`; audit projection absence does not invalidate an authorization | ADR 0010; [Recovery](../architecture/recovery.md) |
| Deterministic policy replay | **Partial** | Replay re-runs the recorded risk algorithm via a versioned registry and reports field-level mismatches (score, band, reasons, outcome, recomputed canonical input hash, reason-catalog validity) without any operational side effect (ADR 0019, ADR 0020); normalized-input schema and decision-engine versions are surfaced but not yet compared against a live known-set; application commit SHA and recovery-classification comparison remain out of scope | ADR 0020; [#43](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/43) |
| Shadow policy evaluation | **Implemented** | Candidate policy versions can be evaluated side-effect-free against a trace | ADR 0006 |
| Tamper-evident hash chain | **Implemented** | Every `decision_trace` row is chained by content hash (including its reasons); bounded-range verification, a forward-only checkpointed integrity job, metrics, and operator diagnostics exist. Does not defend against a sustained, privileged attacker who also recomputes downstream hashes -- see ADR 0027's Limitations | ADR 0027, [#40](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/40) |
| Signed evidence bundles | **Implemented** | Exports a canonical, hash-manifested, digitally signed bundle (decision metadata, normalized input, reasons, policy/algorithm versions, replay result, audit-chain proof) for a single historical decision; raw account reference is pseudonymized by default; every export is recorded (actor, reason) in an append-only log. `verify` proves internal self-consistency but cannot independently confirm the embedded public key's real-world identity -- see ADR 0028's Limitations | ADR 0028, [#42](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/42) |

## Challenge orchestration

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Purpose-bound challenge lifecycle | **Implemented** | Creation, verification attempts, expiry, retry budget, terminal states, and single-use consumption are modeled | ADR 0004 |
| Simulated TOTP, email, and WebAuthn modes | **Partial** | Provider types exist for demonstration, but their current secret behavior is not production-grade | Secrecy/provider separation: [#20](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/20) |
| Recovery identity challenge binding | **Implemented** | Challenge is bound to `RECOVERY_IDENTITY`, recovery ID, and authorization-owned account reference | ADR 0010 |
| Concurrent verification hardening | **Planned** | Optimistic locking and one-winner terminal transition need dedicated coverage | [#20](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/20), [#37](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/37) |
| Production-profile simulation guard | **Planned** | Simulated providers are not yet blocked by production-like profile rules | [#38](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/38) |

## Recovery

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Recovery state machine | **Implemented** | Enforces `VERIFYING_IDENTITY`, immediate, delayed, manual-review, completed, rejected, and failure paths | ADR 0005; [Recovery](../architecture/recovery.md) |
| Risk classification gates | **Implemented** | Scores 0–30 are immediate, 31–60 delayed, and 61–100 manual review; gates remain enforced after identity proof | Issue #16; ADR 0005 |
| Explicit recovery authorization | **Implemented** | `START_RECOVERY` emits an immutable authorization carrying account, directive, risk, decision and request provenance | Issue #30; ADR 0010 |
| Authorization expiry and single consumption | **Implemented** | Authorization expires after 15 minutes, is locked pessimistically, and can create one flow | Migration V10; ADR 0010 |
| Equivalent initiation retry | **Implemented** | The same authorization returns the existing flow and does not create a second challenge | `RecoveryIntegrationTest` |
| Concurrent initiation under multiple threads | **Partial** | Database uniqueness and authorization lock exist; a dedicated multi-thread Testcontainers proof and stable race mapping remain | [#18](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/18) |
| Recovery optimistic locking | **Planned** | `RecoveryFlowEntity` does not yet expose controlled stale-update conflicts | [#18](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/18), [#37](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/37) |
| Versioned recovery classification provenance | **Planned** | Authorization stores directive and risk, but classification-rule version is not yet frozen explicitly | [#31](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/31) |
| Recovery retention policy | **Implemented** | Terminal recovery flows and expired recovery authorizations are both purged in bounded batches | `RecoveryFlowRetentionCleanup`, `RecoveryAuthorizationRetentionCleanup` (`recovery/internal`); ADR 0024 |

## Transactional events and outbox

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Transactional outbox write | **Implemented** | Domain events are stored in PostgreSQL in the originating transaction | ADR 0023 |
| Simulated relay | **Implemented** | A relay atomically claims pending records (`FOR UPDATE SKIP LOCKED`) and publishes them through a simulated publisher with bounded exponential backoff and jitter | ADR 0023 |
| Multi-instance claiming and backoff | **Implemented** | Explicit `PENDING`/`IN_PROGRESS`/`PUBLISHED`/`DEAD_LETTERED` states, atomic `SKIP LOCKED` claiming, jittered backoff, visible dead letters excluded from polling, and an operator-restricted requeue endpoint | ADR 0023; [#23](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/23) |
| Versioned minimized integration events | **Implemented** | Every outbox payload is wrapped in a versioned envelope (`eventId`/`schemaVersion`/`correlationId`/`occurredAt`); account references are pseudonymized before serialization | ADR 0023; [#23](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/23), [#32](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/32) |
| Signed webhook delivery | **Implemented** | Subscriptions, per-secret HMAC-SHA256 signing, timestamp/delivery-ID replay protection (proven against an in-process demo receiver), secret rotation, and delivery history are implemented, backed by the existing outbox retry/backoff/dead-letter loop; `audit.integrity.failed` (ADR 0027) is now wired in | ADR 0026, ADR 0027, [#47](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/47) |
| Versioned AsyncAPI event contracts and compatibility gate | **Implemented** | Hand-authored AsyncAPI 3.0 document for all six integration event types; CI diffs each event's real wire fixture against a checked-in baseline and checks domain-enum constant sets, self-bootstrapping since no tagged release exists yet | ADR 0029, [#52](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/52) |

## API, security, and data protection

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| OpenAPI-described HTTP endpoints | **Implemented** | Protection, challenge, recovery, policy, simulation, and operational contracts are exposed and tested | Runtime OpenAPI configuration |
| OpenAPI backward-compatibility gate | **Implemented** | CI diffs the live `/v3/api-docs` document against a checked-in baseline (endpoint/method removal, removed fields, incompatible type changes, new required fields, enum removals); self-bootstrapping since no tagged release exists yet | ADR 0029, [#52](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/52) |
| Generic recovery authorization errors | **Implemented** | Missing, expired, or inconsistent recovery authorization is non-enumerable | ADR 0010 |
| Standard RFC 9457 problem-code catalog | **Partial** | Problem Details exist, but stable codes and consistent internal diagnostics are incomplete | [#36](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/36) |
| API authentication and RBAC | **Planned** | Sensitive endpoints are not yet protected by Spring Security roles | [#19](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/19) |
| Fresh step-up for privileged actions | **Planned** | Purpose-bound administrative authorization is not implemented | [#48](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/48) |
| Data classification and pseudonymization | **Partial** | Opaque references and redaction guidance exist; systematic subject tokens and retention enforcement do not | [#32](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/32) |
| Encryption rotation and crypto-shredding | **Partial** | Envelope encryption, KEK rotation, and crypto-shredding are implemented for `protection_request.account_reference`; `decision_trace` and the challenge/recovery tables still carry it in plaintext | ADR 0025, [#49](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/49) |

## Persistence and concurrency

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| PostgreSQL source of truth | **Implemented** | Decisions, policy versions, audit, idempotency, challenges, recovery, authorization, and outbox are persisted | ADR 0002 |
| Flyway migration history | **Implemented** | Schema evolution, backfills, constraints, triggers, and seed policy changes are versioned | `src/main/resources/db/migration/` |
| Audit and authorization immutability | **Implemented** | PostgreSQL triggers prevent unsupported updates to evidence and authorization fields | Persistence integration tests |
| Broad domain check constraints | **Partial** | Several important ranges and uniqueness rules exist; remaining state/timestamp constraints are tracked | [#37](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/37) |
| Optimistic locking across mutable aggregates | **Planned** | Recovery and challenge require `@Version` and stable stale-write mapping | [#37](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/37) |
| Database least privilege | **Partial** | Restricted `accountshield_runtime`/`accountshield_readonly` roles exist with correct grants (proven via a dedicated permission test); the application's own deployed connection has not been switched to the restricted role within this repository's tooling, since doing so for the shared Testcontainers test suite is a separate, larger effort (ADR 0024) | ADR 0024; [#25](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/25) |
| Automated retention | **Implemented** | All temporal tables (idempotency, challenges, recovery flows, recovery authorizations, outbox published/dead-lettered rows) have bounded cleanup jobs with retention metrics | ADR 0023, ADR 0024 |

## Observability and operations

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Structured security logs and metrics | **Implemented** | Decision, challenge, recovery, policy, and outbox activity has structured baseline instrumentation | Existing Micrometer/logging tests |
| Transaction-aware success instrumentation | **Implemented** | Success metrics/logs use `@TransactionalEventListener(AFTER_COMMIT)` (two denied-privileged-attempt security logs deliberately remain synchronous); a real duration `Timer` (explicit SLO histogram buckets) backs the SLO doc and dashboard; a generic failed-decision counter covers rollback/failure paths | ADR 0030, [#24](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/24) |
| Distributed tracing | **Partial** | Micrometer Tracing + OTLP export wired in (automatic HTTP-request-level spans); Jaeger (OTLP-native) added to Compose. Named per-module spans (risk/policy/audit/challenge/recovery/outbox) were attempted via `@Observed` but reverted after `spring-boot-starter-aop` failed to resolve in CI; not yet validated end-to-end by actually running the stack in this environment | ADR 0030, [#24](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/24) |
| Backup and restore drill | **Implemented** | A real `pg_dump`/`psql` backup-and-restore drill against a second, independently-bootstrapped Spring context validates Flyway migrations, audit-chain integrity, active-policy uniqueness, and outbox republish-prevention after restore; demonstrates RPO via an explicit data-loss boundary and measures RTO by phase (nightly-only, `@Tag("disaster-recovery")`) | ADR 0036, [#51](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/51) |
| Reproducible capacity benchmark | **Implemented** | All 8 named dimensions (decision throughput/latency, policy evaluation, persistence, outbox publish, replay, database growth/index impact, connection-pool saturation, audit/hash-chain overhead) measured against the real Spring context and a real Postgres instance; report-only Markdown artifacts (no hardcoded numbers, no historical baseline yet), `@Tag("benchmark")`/nightly split with a small untagged default-gate smoke test | ADR 0035, [#50](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/50) |

## Testing, delivery, and user surfaces

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Unit and PostgreSQL integration tests | **Implemented** | Maven verification covers domain boundaries, migrations, Spring context, and integration behavior | Current CI baseline: 181 tests at commit above |
| Spring Modulith and architecture verification | **Implemented** | Module boundaries and architecture rules are verified in CI | `ArchitectureTest` and application verification |
| Docker image build and smoke test | **Implemented** | CI builds the backend image after Maven verification, then actually starts it against a real Postgres container and polls `/actuator/health` before passing | `.github/workflows/ci.yml`, ADR 0031 |
| Failure diagnostic artifacts | **Implemented** | CI prints Surefire root causes and uploads reports on failure | `.github/workflows/ci.yml` |
| Supply-chain security gates | **Partial** | CodeQL, a hard-gating dependency review (new critical/high vulnerabilities fail the PR), advisory Trivy filesystem/image scans, advisory Gitleaks, Dependabot, and a CycloneDX SBOM (build artifact, not yet attached to a release) are implemented; coverage thresholds, SpotBugs, and Checkstyle remain -- no real baseline exists yet to set them against | ADR 0031, [#27](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/27) |
| Property-based tests and API fuzzing | **Implemented** | jqwik property tests for risk-score range (a real jqwik `@Property`, which caught a real bound error on its first run), idempotency equivalence/conflict, and challenge terminal-state monotonicity; a curated malformed-request/header fuzz test asserting no 5xx and no internal-detail leakage. Bounded tries by default (`junit-platform.properties`), deeper fuzzing nightly. Not a full OpenAPI-spec-driven fuzzer | ADR 0033, [#53](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/53) |
| Resilience and concurrency fault-injection suite | **Implemented** | Of the 8 scenarios issue #39 named, 4 were already covered by prior tests (commit-failure rollback, multi-instance outbox claiming, concurrent policy activation, unavailable historical algorithm versions); this closes the remaining 4 (outbox reclaim after process failure, concurrent challenge verification/consumption, recovery clock boundaries, Toxiproxy-based DB latency/connection interruption). `@Tag("resilience")` splits the slow Toxiproxy scenario into a nightly-only workflow | ADR 0032, [#39](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/39) |
| Adversarial scenario laboratory | **Partial** | 5 of 9 named scenarios implemented (credential stuffing, impossible travel, device takeover, MFA fatigue, recovery abuse) with hand-verified expected scores/outcomes, each exporting and verifying a real signed evidence bundle; a shared Markdown decision-timeline report is uploaded as a CI artifact. SIM swap, session replay, insider misuse, and password spraying are deferred -- this system currently has no signal/telemetry field for any of them | ADR 0034, [#54](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/54) |
| Operator console | **Planned** | Frontend foundation exists only in open PR #58 and is not part of `main` | [#41](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/41), [PR #58](https://github.com/vinicius-ssantos/accountshield-orchestrator/pull/58) |
| Java SDK and integration demo | **Partial** | `accountshield-sdk` (standalone Maven module, no server dependency) has typed protection/challenge/recovery clients, per-operation-explicit safe retries, typed Problem Details, and an independent webhook-signature/replay verifier, contract-verified against a live instance. `accountshield-demo` is a real CLI consumer (not a second web UI) demonstrating all three outcomes plus webhook verification, wired into CI's end-to-end check and an opt-in Compose profile. Not yet published to any artifact repository; the demo's webhook step signs its own sample payload rather than wiring a live, authenticated subscription (issue #19/#48 scope) | ADR 0037, [#55](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/55) |
| Scenario CLI | **Partial** | `accountshield-cli` (standalone Maven module, built entirely on the SDK) executes the 5 deterministic scenarios from ADR 0034 (`scenario list/run/report`, proven end to end by a real subprocess test), plus `policy lint/diff` and `evidence verify` (real backing APIs; `policy diff` adapted to the real policy-impact endpoint shape, no literal "stable version" argument). Documented exit-code and stable-JSON contract. Executable JAR only -- platform-specific native binaries deferred until a release pipeline exists (issue #28) | ADR 0038, [#56](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/56) |
| Repository governance and reproducible release | **Partial** | 23 issues with real merged work (never auto-closed by GitHub) closed and verified; Epic #15 updated to match reality; PR/issue templates, CODEOWNERS, Conventional Commits (`CONTRIBUTING.md`), a generated `CHANGELOG.md`, and a tag-triggered GHCR release workflow (`.github/workflows/release.yml`) all exist; real demo package (curl walkthrough, Postman collection, seed script, interview script). The `v1.0.0` tag itself and branch protection are deliberately not yet applied -- both are externally-visible, hard-to-reverse actions requiring explicit owner confirmation; Grafana screenshots need a live-running stack | ADR 0039, [#28](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/28) |

## Explicit non-goals for the current release

- storing passwords or acting as an identity provider;
- processing real MFA secrets or real fraud decisions;
- claiming production readiness;
- splitting the modular monolith without measured deployment or ownership need;
- introducing Kafka, Kubernetes, or distributed caches only for architectural appearance;
- using real personal data in tests or demonstrations.

## Updating this catalog

A feature PR must update this file in the same pull request when it:

- delivers a planned capability;
- closes a named hardening gap;
- changes failure, retry, concurrency, or authorization semantics;
- introduces a new limitation or supersedes an ADR.

Status must reflect merged `main`, never an unmerged branch or chat plan.
