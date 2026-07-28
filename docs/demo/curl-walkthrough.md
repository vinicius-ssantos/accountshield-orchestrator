# Sample curl walkthrough

A complete, copy-paste walkthrough against a running instance (`docker compose up -d` from the
repo root), covering decision, challenge, recovery, replay, policy rollout/impact, audit, and
outbox -- issue #28's explicit demo coverage list. The [Scenario CLI](../../cli/README.md) is the
recommended primary walkthrough; this page is the equivalent raw-HTTP version for anyone who wants
to see the exact wire requests, or doesn't want to build the CLI/SDK.

All account references below are synthetic (`.test` domain) -- never real personal data.

## 0. Get a bearer token

Every endpoint below except `/demo/webhook-receiver` requires a JWT (ADR 0011). The `local`
Spring profile (set by default in `compose.yaml`'s `app` service) exposes a dev-only token
issuer:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/dev/tokens \
  -H "Content-Type: application/json" \
  -d '{"subject":"demo-walkthrough","roles":["PROTECTION_CLIENT","POLICY_ADMIN","SIMULATION_ANALYST","SECURITY_OPERATOR"]}' \
  | jq -r '.token')
```

## 1. Decision

```bash
curl -s -X POST http://localhost:8080/api/v1/protection-decisions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "accountReference": "curl-walkthrough-'"$(uuidgen)"'@example.test",
    "eventType": "LOGIN_ATTEMPT",
    "impossibleTravel": true,
    "newDevice": true,
    "networkRiskLevel": "MEDIUM",
    "idempotencyKey": "curl-walkthrough-'"$(uuidgen)"'"
  }' | jq .
```

Expect `"outcome":"REQUIRE_STEP_UP"`, `"riskScore":60`, and a `challenge` object -- save
`protectionRequestId`, `decisionId`, and `challenge.challengeId` from the response for the next
steps.

## 2. Challenge

```bash
curl -s -X POST http://localhost:8080/api/v1/challenges/<challengeId>/verify \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"providedCode":"000000","purpose":"PROTECTION_STEP_UP","contextId":"<protectionRequestId>"}' | jq .
```

A wrong code returns `"verified":false"` with `remainingAttempts` decremented -- the challenge's
own budget (3 attempts) is enforced server-side regardless of caller retries.

## 3. Recovery

Trigger `START_RECOVERY` with a higher-risk, recovery-context event:

```bash
curl -s -X POST http://localhost:8080/api/v1/protection-decisions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "accountReference": "curl-recovery-'"$(uuidgen)"'@example.test",
    "eventType": "PASSWORD_RESET_ATTEMPT",
    "compromisedCredential": true,
    "impossibleTravel": true,
    "networkRiskLevel": "LOW",
    "idempotencyKey": "curl-recovery-'"$(uuidgen)"'"
  }' | jq .
```

Expect `"outcome":"START_RECOVERY"` and a `recoveryAuthorizationId`. Initiate the flow:

```bash
curl -s -X POST http://localhost:8080/api/v1/recovery \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"authorizationId":"<recoveryAuthorizationId>"}' | jq .
```

## 4. Replay

Using the `protectionRequestId` from step 1:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/simulation/replay/<protectionRequestId> | jq .
```

Expect `"matches":true` -- the historical risk algorithm and policy version are re-run
side-effect-free and reproduce the original score/outcome exactly.

## 5. Policy rollout / impact analysis

Lint a candidate threshold set (no draft created, no side effects):

```bash
curl -s -X POST http://localhost:8080/api/v1/policies/analyze \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"allowMaxScore":29,"stepUpMaxScore":69,"recoveryMaxScore":89}' | jq .
```

Diff a candidate version against recent recorded history for the live policy:

```bash
curl -s -X POST "http://localhost:8080/api/v1/simulation/policy-impact?policyKey=account-protection-default&candidatePolicyVersion=1.1.0&maxSamples=100" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## 6. Audit

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/audit/chain/verify?from=1&to=10" | jq .
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/audit/chain/root-hash | jq .
```

Expect `"valid":true` -- every decision-trace row's content hash and chain linkage recomputes
cleanly.

## 7. Outbox

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/outbox?status=PENDING" | jq .
```

Lists pending outbox events (each protection decision above published one); the outbox relay
publishes them automatically on its own schedule.
