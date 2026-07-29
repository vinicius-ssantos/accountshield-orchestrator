# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `test:`, `docs:`,
`chore:`, ...) -- see `CONTRIBUTING.md`. Every entry below is generated from real, merged commit
history on `main` (`git log --oneline`), not written aspirationally; routine dependency-bump
commits (`chore(deps):`, Dependabot) are omitted here for readability and tracked in each PR's own
history instead.

## [Unreleased]

### Fixed

**Post-v1.0.0 self-review, first pass (#141)**
- Production boot now fails fast if any operator-managed secret (challenge HMAC, pseudonym,
  webhook encryption key, KEK, subject-id) is still at its repository-published default; KEK and
  webhook secret-encryption keys must be real base64-encoded 32-byte key material, not a
  passphrase run through a bare hash.
- `/dev/tokens` scoped to its exact path instead of a `/dev/**` wildcard.
- All four Maven modules (root, sdk, cli, demo) versioned to match the tagged release; the release
  workflow uses a glob for the SDK jar instead of a hardcoded snapshot filename.
- `CliEndToEndTest` skips instead of hard-failing when the standalone CLI jar hasn't been built,
  keeping a clean-clone `./mvnw verify` self-sufficient for the parts that don't need it.
- A committed, JaCoCo-enforced 80% instruction-coverage floor.
- `GET /api/v1/outbox` now paginated with a hard cap, closing an unbounded-load vector.
- The OpenAPI compatibility baseline is committed and pinned instead of self-bootstrapping.
- Dead `MONITOR` value removed from the decision-outcome database constraint.
- Crypto-shredding's "irrecoverable" guarantee documented against backups, WAL, and pre-VACUUM
  tuples; the audit hash chain's `normalized_context` exclusion documented as a boundary.

**Post-v1.0.0 self-review, second pass**
- (#144) `/demo/webhook-receiver` scoped to the `local` profile, matching `/dev/tokens`; its secret
  added to the production fail-fast guard as a backstop.
- (#143) `SubjectKeyRewrapJob` can no longer resurrect an already crypto-shredded subject key by
  racing a concurrent shred; its batch claim now uses `FOR UPDATE SKIP LOCKED`.
- (#145) Outbox acknowledgements (publish/backoff/dead-letter) now fenced by a per-claim token, so
  a stale claim owner reclaimed by a newer worker can no longer overwrite state the new owner
  already wrote.
- (#147) The SDK's `RetryPolicy` backoff exponent is actually capped now -- the earlier claimed fix
  never touched the overflow-prone code path.
- (#149) The in-memory rate limiter evicts stale, empty windows on a schedule instead of growing
  without bound for the life of the process.
- (#150) The audit chain integrity checkpoint is monotonic (`GREATEST`) and its read-verify-advance
  cycle is now serialized with an advisory lock, closing a checkpoint-regression race between
  concurrent scheduled ticks.

## [1.0.0] - 2026-07-28

First tagged release. Everything below has been implemented and merged to `main` (see
`docs/roadmap.md`'s delivery status and Epic #15 for the individually-verified completion record
of every issue referenced here).

### Added

**Core decision engine and policy lifecycle**
- Deterministic, explainable risk scoring with bounded score, band, and ordered reason codes.
- Versioned policy lifecycle: draft, validate, approve (maker-checker, #33), activate, reject,
  retire, with immutable activated versions.
- Static policy-threshold analyzer/linter as a real `validate()` gate (#46).
- Historical policy impact analysis with a full transition matrix and segment breakdowns (#35).
- Deterministic canary rollout with cohort-hash traffic splitting and immediate rollback (#34).
- Client/event-aware policy routing (#26).

**Recovery and challenge orchestration**
- Explicit, risk-gated recovery state machine with an immutable, expirable, single-use
  `RecoveryAuthorization` (audit as evidence, never execution authority).
- Idempotent recovery initiation with concurrency-proven persistence hardening (#18).
- Versioned recovery classification provenance (#31).
- Fresh, purpose-bound step-up authorization for privileged operations (#48).
- Purpose-bound challenge lifecycle (TOTP/e-mail/WebAuthn simulated providers) with hashed proof
  material, concurrent-verification hardening, and a production-profile simulation guard (#20, #38).

**Provenance, replay, and evidence**
- Deterministic replay via a self-registering, versioned risk-algorithm registry, with recomputed
  canonical-input-hash and reason-catalog/decision-engine provenance (#21, #43).
- Risk-signal provenance envelope with staleness rejection and confidence-based scoring (#45).
- Tamper-evident audit hash chaining with bounded-range verification and a checkpointed integrity
  job (#40).
- Signed, redacted decision evidence bundles, independently verifiable (#42).
- Adversarial account-takeover scenario laboratory covering 5 hand-verified attack patterns (#54).

**Reliability and integration surfaces**
- Concurrency-safe idempotency claimed before any side effect, not recorded after (#22).
- Transactional outbox with atomic `SKIP LOCKED` claiming, bounded backoff, and visible dead
  letters (#23).
- Signed webhook delivery with HMAC replay protection (#47).
- OpenAPI and AsyncAPI backward-compatibility gates, self-bootstrapping baselines (#52).
- Envelope encryption, key rotation, and crypto-shredding for `account_reference` (#49).
- Database least-privilege roles and closed referential-integrity gaps (#25).
- Data classification, pseudonymization, and retention model (#32).

**Authentication, authorization, and standards**
- JWT resource-server authentication and role-based authorization for sensitive APIs (#19).
- Standardized RFC 9457 Problem Details with request correlation IDs (#36).

**Observability, resilience, and measured operations**
- Transaction-aware metrics/logging (`@TransactionalEventListener(AFTER_COMMIT)`) and distributed
  tracing via Micrometer + OTLP + Jaeger (#24).
- Resilience and concurrency fault-injection suite (#39).
- Property-based tests (jqwik) and curated API fuzzing (#53).
- Reproducible capacity/performance benchmark across 8 measured dimensions (#50).
- Executable backup, restore, and disaster-recovery drill with measured RPO/RTO (#51).

**Supply chain, CI, and platform**
- CI and software-supply-chain security: CodeQL, dependency review, Trivy/Gitleaks, CycloneDX SBOM,
  Dependabot, a real container smoke test (#27).
- Standalone `accountshield-sdk` Java client module with typed clients, safe retries, and an
  independent webhook-signature verifier, plus an `accountshield-demo` consumer (#55).
- Standalone `accountshield-cli` Scenario CLI (`scenario`/`policy`/`evidence` commands) built on
  the SDK (#56).
- Repository governance: PR/issue templates, CODEOWNERS, Conventional Commits, this changelog
  (#28).

### Fixed

- Recovery trust-boundary flaws: bind challenges to purpose and consume once; authorize recovery
  only from an explicit decision; decouple recovery authorization from the audit read model.
- Enforced remaining domain invariants (constraints, optimistic locking) at the database layer (#37).
- Challenge secrecy hardening: hashed/HMAC stored proof material, split provider contracts,
  concurrent-verification single-winner semantics (#20).
- Simulated challenge providers now fail application startup outright under a production-like
  profile instead of silently running (#38).

## Versioning and release policy

Until the first tagged release, only the latest commit on `main` is supported for security fixes
(see `SECURITY.md`). Once a `v1.0.0` tag exists, this file's `[Unreleased]` section becomes
`[1.0.0]` and future changes accumulate under a new `[Unreleased]` heading, following
[Semantic Versioning](https://semver.org/).
