# ADR 0007: Policy lifecycle state machine with immutable activation

- Status: Accepted
- Date: 2026-07-22

## Context

Policies must evolve — thresholds change, new signals are weighted differently, and new outcomes are introduced. But once a policy version is activated and used to make decisions, it must be immutable: the same version number must always produce the same outcome for the same inputs. This is required for deterministic replay and audit reproducibility.

The platform needs a controlled lifecycle: policies are drafted, validated, activated, and eventually retired or rejected. Only one version of a policy key can be active at any time. Activation of a new version must atomically retire the previous one.

## Decision

Introduce a `PolicyLifecycleService` in the `policy` module with a five-state lifecycle enforced both in the domain entity and at the database level.

### State machine

```
DRAFT     -> VALIDATED   (operator validates thresholds)
DRAFT     -> REJECTED    (operator discards)
VALIDATED -> ACTIVE      (operator activates; previous ACTIVE auto-retired)
VALIDATED -> REJECTED    (operator discards after validation)
ACTIVE    -> RETIRED     (operator retires, or auto-retired by new activation)
```

Terminal states: `RETIRED`, `REJECTED`. No transitions are possible from terminal states.

### Activation semantics

When `activate(policyKey, version)` is called:
1. The currently active version for that key (if any) transitions to `RETIRED`.
2. The candidate version transitions from `VALIDATED` to `ACTIVE` with `activated_at` set.
3. Both transitions occur in a single transaction.

The partial unique index `uq_single_active_policy ON policy.policy_version (policy_key) WHERE status = 'ACTIVE'` enforces single-active at the database level.

### Immutability guarantee

A PostgreSQL trigger (`protect_activated_policy_version`) blocks all updates to rows with `status = 'ACTIVE'`, except for the narrow case of transitioning to `RETIRED`. The trigger verifies that no fields other than `status` change during that transition.

This means:
- An activated policy's definition, version string, and thresholds are frozen.
- Replays against an active or retired version always see the same definition.
- The trigger is the last line of defense — even if application logic has a bug, the database rejects the mutation.

### Draft creation guard

`createDraft` rejects creation if a `DRAFT` or `VALIDATED` version already exists for the same key. This prevents confusion from multiple pending versions.

### Entity-level enforcement

`PolicyVersionEntity.transitionTo(targetStatus, now)` validates the transition against the current status before applying. Invalid transitions throw `IllegalPolicyTransitionException`, which the controller maps to HTTP 409 Conflict.

### V6 migration

The original immutability trigger (V1) blocked all updates to active rows. V6 replaces it with a version that allows only the `ACTIVE -> RETIRED` transition while keeping all other fields immutable.

### API

```
POST /api/v1/policies                             — create draft
POST /api/v1/policies/{key}/{version}/validate     — validate draft
POST /api/v1/policies/{key}/{version}/activate     — activate (auto-retires previous)
POST /api/v1/policies/{key}/{version}/reject       — reject draft or validated
POST /api/v1/policies/{key}/{version}/retire       — retire active
```

## Consequences

### Positive

- five-state lifecycle with explicit, auditable transitions;
- immutability enforced at both domain and database levels (defense in depth);
- atomic version swap prevents windows with zero or two active policies;
- shadow evaluation (ADR 0006) can safely compare candidate versions before activation;
- replay determinism is guaranteed because active/retired definitions never change.

### Negative

- threshold corrections always require a new version (no in-place patching);
- the trigger complicates direct SQL maintenance of policy rows;
- no policy diff or rollback-to-previous capability yet.

## Guardrails

- transition validation runs in the entity before the transaction commits;
- the DB trigger is a hard immutability guarantee independent of application logic;
- `IllegalPolicyTransitionException` surfaces as 409 Conflict, not 500;
- only one `DRAFT` or `VALIDATED` version per key at a time.

## Revisit criteria

This decision may be revisited when:

- policy rollback (re-activate a retired version) is needed;
- staged/canary activation is required;
- policy templates or inheritance are introduced;
- multi-tenant policy isolation is needed.
