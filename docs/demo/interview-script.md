# Interview / demo script

A ~10-minute narrated walkthrough for demonstrating AccountShield live, covering decision,
challenge, recovery, replay, policy rollout, audit, and outbox (issue #28's explicit coverage
list). Pairs with `docs/demo/curl-walkthrough.md` (raw HTTP) or the
[Scenario CLI](../../cli/README.md) (recommended -- faster to run live, prints a clean event
timeline). Run `docker compose up -d` and `scripts/seed-demo-data.sh` before starting.

## 0. Framing (30s)

"AccountShield is an account-protection *decision and orchestration* platform, not an identity
provider -- it doesn't authenticate users or store passwords. It takes risk signals about a login
or account event and returns one of four explainable outcomes: ALLOW, REQUIRE_STEP_UP,
START_RECOVERY, or TEMPORARILY_BLOCK, backed by an immutable, replayable audit trail. This is a
portfolio/educational project -- see `SECURITY.md` for the explicit non-production-readiness
disclaimer I hold myself to."

## 1. Decision (90s)

Run: `accountshield-cli scenario run credential-stuffing --token "$TOKEN"`

"This submits a real protection decision -- 10 failed attempts, a compromised credential, a new
device, medium network risk. The response is a versioned, explainable decision: score 95, exact
reason codes, the policy key/version and risk-algorithm version that produced it, and a
TEMPORARILY_BLOCK outcome -- all before any challenge is issued, which is itself an explicit
security design choice (ADR 0034)."

## 2. Step-up challenge (60s)

Run: `accountshield-cli scenario run impossible-travel --token "$TOKEN"`

"Impossible travel plus a new device scores 60 -- REQUIRE_STEP_UP, with a real challenge issued.
The CLI submits a deliberately wrong code to show the response shape; note `remainingAttempts`
decrementing -- that budget is enforced server-side, not client-trusted."

## 3. Recovery (60s)

Run: `accountshield-cli scenario run recovery-abuse --token "$TOKEN"`

"A password-reset attempt with a compromised credential and impossible travel scores 75 -- on an
ordinary login that would TEMPORARILY_BLOCK, but recovery-context events use a higher threshold
and route into START_RECOVERY instead: an immutable, single-use, 15-minute-expiring
`RecoveryAuthorization`. Audit is evidence here, never the authority that lets recovery proceed --
that trust-boundary separation is ADR 0010's core decision."

## 4. Replay (45s)

"Every decision can be replayed deterministically -- the exact historical risk algorithm version
is looked up and re-run against the exact recorded input, side-effect-free, and the result is
diffed against what actually happened." Show `GET /api/v1/simulation/replay/<protectionRequestId>`
from the curl walkthrough, or narrate `PolicyRolloutIntegrationTest`/`SimulationIntegrationTest` if
live replay isn't convenient mid-demo.

## 5. Policy rollout and impact (60s)

Run: `accountshield-cli policy diff account-protection-default 1.1.0 --token "$TOKEN"`

"This replays real recent decisions against a candidate policy version and reports a full
ALLOW/STEP_UP/BLOCK/RECOVERY transition matrix plus a divergence percentage -- the same mechanism
that gates a canary rollout (ADR 0022) before it ever reaches live traffic."

## 6. Audit integrity (45s)

"Every decision-trace row is chained by content hash, including its reasons -- tamper or reorder
one row and the chain breaks detectably." Show `GET /api/v1/audit/chain/verify?from=1&to=N`
reporting `valid: true`, and mention the evidence-bundle export/verify flow (`evidence verify`)
as the signed, portable, independently-checkable version of the same guarantee.

## 7. Outbox and webhooks (45s)

"Every decision publishes an integration event through a transactional outbox -- atomic with the
decision itself, with `SKIP LOCKED` claiming for multi-instance safety, bounded backoff, and
visible dead letters rather than silent message loss. Outbound webhooks are HMAC-signed with
replay protection on the receiving end." Show `GET /api/v1/outbox?status=PENDING`.

## Close (30s)

"Everything just shown is real, executable code on `main` -- the feature catalog
(`docs/features/README.md`) and the ADR index (`docs/adr/README.md`) are the actual source of
truth for what's implemented versus planned, not this script's narration."
