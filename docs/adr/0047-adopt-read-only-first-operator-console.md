# ADR 0012: Adopt a read-only-first operator console

- Status: Accepted
- Date: 2026-07-23

## Context

AccountShield exposes security-sensitive capabilities: protection decisions, recovery workflows, policy lifecycle operations, replay, and outbox or dead-letter handling.

A frontend can make these capabilities easier to operate, but it can also create a dangerous impression that hiding a control or disabling a button is sufficient authorization. Administrative mutations are not safe until the backend provides explicit identity, role, step-up, approval, audit, masking, provenance, and stable failure semantics.

The initial console must provide product value without creating an accidental administrative control plane.

## Decision

Deliver the frontend as a read-only operator console first.

The initial product surfaces may display synthetic or authorized read models, decision explanations, policy provenance, recovery status, replay comparisons, and operational health. They must not activate policies, approve recoveries, replay dead-letter messages, roll back versions, or execute other privileged commands.

A mutation is enabled only when the corresponding backend contract enforces all required controls independently of the UI.

## Mutation readiness gate

Before a privileged command is exposed, the owning slice must provide:

- authenticated operator identity;
- backend-enforced RBAC or finer-grained authorization;
- purpose-bound fresh step-up authorization where required;
- maker-checker approval for high-impact policy or recovery actions where required;
- explicit reason or justification capture;
- idempotency and concurrency behavior;
- immutable audit records with actor, target, reason, result, and correlation identifiers;
- sensitive-data masking and output minimization;
- stable Problem Details errors that do not leak security-sensitive state;
- contract tests and adversarial authorization tests;
- a clearly documented rollback or recovery procedure.

## Consequences

### Positive

- investigation and explainability can ship before administrative control-plane security is complete;
- the UI cannot become the only authorization boundary;
- dangerous workflows gain explicit backend readiness criteria;
- fixture-driven demonstrations remain safe and deterministic;
- future reviewers can identify why a visible read model does not imply mutation permission.

### Negative

- some operator workflows require temporary use of backend APIs or development tooling;
- the console may appear incomplete during early releases;
- read and write models may need separate delivery milestones;
- adding a mutation requires coordinated backend, frontend, documentation, and security testing work.

## Guardrails

- authorization decisions are never inferred from hidden, disabled, or absent controls;
- fixture mode cannot invoke live backend mutations;
- read-only status must be visible on incomplete operational surfaces;
- buttons that imply unavailable privileged actions must not be rendered as functioning controls;
- correlation IDs are operational references and never authorization credentials;
- route availability does not imply command authorization;
- frontend telemetry must not contain secrets, raw identifiers, challenge material, or privileged payloads.

## Alternatives considered

### Build full administrative workflows immediately

Rejected because the backend security contracts and approval semantics must precede the UI that invokes them.

### Depend on frontend role checks initially

Rejected because client-side checks are presentation behavior, not an authorization boundary.

### Omit the console until every mutation is ready

Rejected because read-only investigation, explainability, replay comparison, and operational visibility provide meaningful value independently.

## Revisit criteria

This decision remains valid as the default operating model. Individual mutations may be enabled when their readiness gate is satisfied and recorded in the relevant implementation documentation or a new ADR when the workflow introduces a durable architectural decision.
