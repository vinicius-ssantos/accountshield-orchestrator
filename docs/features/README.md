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
| Versioned policy evaluation | **Implemented** | Active immutable policy versions route standard and recovery-request events | ADR 0007; analyzer planned in [#46](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/46) |
| Explicit protection outcomes | **Implemented** | `ALLOW`, `REQUIRE_STEP_UP`, `START_RECOVERY`, and `TEMPORARILY_BLOCK` are distinct decisions | ADR 0010 |
| Decision idempotency | **Partial** | Repeated sequential equivalent requests return the same logical decision; fingerprint conflicts are detected | Concurrent winner re-read and abstraction cleanup: [#22](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/22) |
| In-memory rate limiting | **Implemented** | Bounded sliding-window limits protect the decision endpoint in the current single-instance demo | ADR 0008; client-aware routing planned in [#26](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/26) |
| Fail-safe dependency degradation | **Planned** | No complete first-class degradation strategy model exists yet | [#44](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/44) |

## Policy lifecycle and governance

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Draft, activation, retirement, immutable versions | **Implemented** | Policy lifecycle is persisted and only one active version is permitted by the current model | ADR 0007 |
| Recovery-specific policy threshold | **Implemented** | Versioned `recoveryMaxScore` produces `START_RECOVERY` for recovery-request events | ADR 0010 |
| Static policy analysis | **Planned** | Semantic diagnostics such as shadowed and contradictory rules are not implemented | [#46](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/46) |
| Maker-checker approval | **Planned** | Actor separation and self-approval prevention are not implemented | [#33](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/33) |
| Canary rollout and rollback | **Planned** | Deterministic cohorts and progressive rollout are not implemented | [#34](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/34) |
| Historical policy impact reports | **Partial** | Shadow evaluation exists for individual traces; aggregate transition reports and approval gates do not | [#35](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/35) |
| Client/event policy routing | **Planned** | Current baseline uses a default policy without tenant/client isolation | [#26](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/26) |

## Audit, replay, and evidence

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Append-only decision trace | **Implemented** | Persists normalized retained context, risk details, policy and algorithm versions, outcome, timestamps, and correlation IDs | ADR 0002; [Persistence](../architecture/persistence.md) |
| Audit as evidence, not recovery authority | **Implemented** | Recovery initiation uses `RecoveryAuthorization`; audit projection absence does not invalidate an authorization | ADR 0010; [Recovery](../architecture/recovery.md) |
| Deterministic policy replay | **Partial** | Historical traces can be replayed without operational side effects using recorded policy context | Full algorithm registry and field-level provenance: [#21](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/21), [#43](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/43) |
| Shadow policy evaluation | **Implemented** | Candidate policy versions can be evaluated side-effect-free against a trace | ADR 0006 |
| Tamper-evident hash chain | **Planned** | Database immutability exists, but cryptographic chain verification does not | [#40](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/40) |
| Signed evidence bundles | **Planned** | Redacted, signed, independently verifiable exports are not implemented | [#42](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/42) |

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
| Recovery retention policy | **Planned** | Terminal-flow cleanup and retention are not implemented | [#18](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/18), [#25](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/25) |

## Transactional events and outbox

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Transactional outbox write | **Implemented** | Domain events are stored in PostgreSQL in the originating transaction | ADR 0009 |
| Simulated relay | **Implemented** | A relay publishes pending records through a simulated publisher with attempts and metrics | ADR 0009 |
| Multi-instance claiming and backoff | **Planned** | Atomic claim states, `SKIP LOCKED`, jittered backoff, and dead letters are not complete | [#23](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/23) |
| Versioned minimized integration events | **Planned** | Internal events still need explicit external schemas and data-minimization rules | [#23](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/23), [#32](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/32) |
| Signed webhook delivery | **Planned** | Subscription, signing, replay protection, delivery history, and secret rotation do not exist | [#47](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/47) |

## API, security, and data protection

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| OpenAPI-described HTTP endpoints | **Implemented** | Protection, challenge, recovery, policy, simulation, and operational contracts are exposed and tested | Runtime OpenAPI configuration |
| Generic recovery authorization errors | **Implemented** | Missing, expired, or inconsistent recovery authorization is non-enumerable | ADR 0010 |
| Standard RFC 9457 problem-code catalog | **Partial** | Problem Details exist, but stable codes and consistent internal diagnostics are incomplete | [#36](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/36) |
| API authentication and RBAC | **Planned** | Sensitive endpoints are not yet protected by Spring Security roles | [#19](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/19) |
| Fresh step-up for privileged actions | **Planned** | Purpose-bound administrative authorization is not implemented | [#48](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/48) |
| Data classification and pseudonymization | **Partial** | Opaque references and redaction guidance exist; systematic subject tokens and retention enforcement do not | [#32](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/32) |
| Encryption rotation and crypto-shredding | **Planned** | Envelope encryption and key lifecycle are not implemented | [#49](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/49) |

## Persistence and concurrency

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| PostgreSQL source of truth | **Implemented** | Decisions, policy versions, audit, idempotency, challenges, recovery, authorization, and outbox are persisted | ADR 0002 |
| Flyway migration history | **Implemented** | Schema evolution, backfills, constraints, triggers, and seed policy changes are versioned | `src/main/resources/db/migration/` |
| Audit and authorization immutability | **Implemented** | PostgreSQL triggers prevent unsupported updates to evidence and authorization fields | Persistence integration tests |
| Broad domain check constraints | **Partial** | Several important ranges and uniqueness rules exist; remaining state/timestamp constraints are tracked | [#37](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/37) |
| Optimistic locking across mutable aggregates | **Planned** | Recovery and challenge require `@Version` and stable stale-write mapping | [#37](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/37) |
| Database least privilege | **Planned** | Migration and runtime currently need separate restricted roles and permission tests | [#25](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/25) |
| Automated retention | **Planned** | Bounded cleanup jobs and retention metrics are not implemented | [#25](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/25), [#32](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/32) |

## Observability and operations

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Structured security logs and metrics | **Implemented** | Decision, challenge, recovery, policy, and outbox activity has structured baseline instrumentation | Existing Micrometer/logging tests |
| Transaction-aware success instrumentation | **Planned** | Some listeners still need `AFTER_COMMIT` semantics and rollback metrics | [#24](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/24) |
| Distributed tracing | **Planned** | Micrometer Tracing, OTLP export, and trace visualization are not implemented | [#24](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/24) |
| Backup and restore drill | **Planned** | Executable RPO/RTO restore procedures do not exist | [#51](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/51) |
| Reproducible capacity benchmark | **Planned** | No published p50/p95/p99 capacity model exists | [#50](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/50) |

## Testing, delivery, and user surfaces

| Capability | Status | Current behavior | Evidence and follow-up |
| --- | --- | --- | --- |
| Unit and PostgreSQL integration tests | **Implemented** | Maven verification covers domain boundaries, migrations, Spring context, and integration behavior | Current CI baseline: 181 tests at commit above |
| Spring Modulith and architecture verification | **Implemented** | Module boundaries and architecture rules are verified in CI | `ArchitectureTest` and application verification |
| Docker image build | **Implemented** | CI builds the backend image after Maven verification | `.github/workflows/ci.yml` |
| Failure diagnostic artifacts | **Implemented** | CI prints Surefire root causes and uploads reports on failure | `.github/workflows/ci.yml` |
| Supply-chain security gates | **Planned** | Coverage thresholds, CodeQL, SBOM, Trivy, dependency review, and pinned actions remain | [#27](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/27) |
| Property-based tests and API fuzzing | **Planned** | jqwik and OpenAPI-aware fuzzing are not implemented | [#53](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/53) |
| Fault-injection laboratory | **Planned** | Full Toxiproxy/crash/race suite is not implemented | [#39](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/39) |
| Adversarial scenario laboratory | **Planned** | Deterministic attack scenarios and reports are not implemented | [#54](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/54) |
| Operator console | **Planned** | Frontend foundation exists only in open PR #58 and is not part of `main` | [#41](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/41), [PR #58](https://github.com/vinicius-ssantos/accountshield-orchestrator/pull/58) |
| Java SDK and integration demo | **Planned** | No external typed SDK is released | [#55](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/55) |
| Scenario CLI | **Planned** | No distributable CLI exists | [#56](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/56) |
| Reproducible 1.0 release | **Planned** | Tagged release, changelog, SBOM, demo bundle, and governance gates remain | [#28](https://github.com/vinicius-ssantos/accountshield-orchestrator/issues/28) |

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
