# ADR 0034: Adversarial account-takeover scenario laboratory

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #54 named 9 candidate scenarios: credential stuffing, password spraying, device takeover,
SIM swap, impossible travel, session replay, MFA fatigue, recovery abuse, insider misuse. Unlike
most large issues this session, `docs/roadmap.md`'s Gate 7 has no dedicated exit-criterion bullet
for this specific issue, so this ADR made its own proportionality call the way ADR 0031 did for
issue #27.

Investigation into this system's actual signal model (`RiskSignals`: `failedAttempts`,
`newDevice`, `impossibleTravel`, `compromisedCredential`, `networkRiskLevel` -- five fields, no
more) found that **5 of the 9 named scenarios map onto it directly and meaningfully; 4 do not**,
because the underlying telemetry those 4 scenarios need simply has no field in this codebase today:

- **SIM swap** needs telecom/carrier-change signals -- no such field exists.
- **Session replay** needs session-token reuse detection -- the only replay protection in this
  codebase (ADR 0026) is for *outbound webhook delivery*, not inbound user sessions.
- **Insider misuse** needs an internal-actor/employee-access context -- this system has no notion
  of an "insider" distinct from an ordinary account holder.
- **Password spraying** is fundamentally a *cross-account* pattern (many accounts, few attempts
  each); this system's risk engine assesses one decision at a time with no cross-account
  correlation view, so a single-decision scenario cannot demonstrate the attack pattern honestly.

Building fake scenarios for these 4 by inventing signals the system doesn't actually consume would
misrepresent what this platform currently detects. They are named explicitly as gaps instead
(Revisit criteria).

## Decision

### 5 scenarios, each with an exactly-computed expected score

Every expected score/reason/outcome below was computed by hand directly from
`DeterministicRiskAssessmentService`'s real scoring formula (`COMPROMISED_CREDENTIAL` +40,
`IMPOSSIBLE_TRAVEL` +35, `FAILED_ATTEMPTS` +3/attempt capped at 30, network risk LOW/MEDIUM/HIGH
+0/10/20, `NEW_DEVICE` +15, `LOW_CONFIDENCE_SIGNAL` +10) and the real, currently-active
`account-protection-default` policy version (**v1.1.0**, confirmed via migration `V9`:
`allowMaxScore=29`, `stepUpMaxScore=69`, `recoveryMaxScore=89`) -- not guessed, and not asserted
against a mocked risk engine or policy.

1. **Credential stuffing** -- `compromisedCredential=true, failedAttempts=10, newDevice=true,
   network=MEDIUM` -> score 95 -> `TEMPORARILY_BLOCK`.
2. **Impossible travel** -- `impossibleTravel=true, newDevice=true, network=MEDIUM` -> score 60 ->
   `REQUIRE_STEP_UP`.
3. **Device takeover** -- `newDevice=true, failedAttempts=3, network=MEDIUM,
   confidence=LOW` -> score 44 -> `REQUIRE_STEP_UP`.
4. **MFA fatigue** -- `newDevice=true, failedAttempts=5, network=HIGH` -> score 50 ->
   `REQUIRE_STEP_UP`, then the scenario submits wrong codes until the issued challenge's own
   attempt budget (3, `ChallengeApplicationService.DEFAULT_MAX_ATTEMPTS`) is exhausted and its
   status transitions to `FAILED` -- exercising the challenge module's real exhaustion path, not
   just the initial risk decision.
5. **Recovery abuse** -- a `PASSWORD_RESET_ATTEMPT` (a recovery-context event type) with
   `compromisedCredential=true, impossibleTravel=true` -> score 75. The same score on an ordinary
   login would `TEMPORARILY_BLOCK` (`stepUpMaxScore=69`), but a recovery-context event is
   evaluated against the higher `recoveryMaxScore=89` -- `START_RECOVERY` instead, demonstrating
   that "abusive" recovery attempts are routed into the recovery flow's own gating (identity
   verification, delay, manual review; ADR 0005/0010) rather than an outright block.

### Each scenario feeds a real evidence bundle

Immediately after `decide()`, every scenario exports a real signed evidence bundle for its decision
(`EvidenceBundleService.exportBundle`, issue #42/ADR 0028) and asserts it verifies cleanly --
directly implementing "results can feed... evidence bundles" with the actual mechanism from that
issue, not a new one.

### A shared, reusable report, not per-scenario dashboards

`ScenarioReport`/`ScenarioReportCollector` (test-support, `scenarios` package) render every
scenario's synthetic input, generated signals/score, selected policy, and decision outcome as a
4-step numbered "timeline" per scenario, aggregated into one Markdown file
(`target/scenario-reports/account-takeover-scenarios.md`), uploaded as a CI artifact (`ci.yml`) --
matching this codebase's existing "generated report -> build artifact" convention (ADR 0031's
coverage/SBOM reports, ADR 0029's contract artifacts) rather than building a new dashboard
mechanism. A scenario whose actual result diverges from its expected values renders with a
`❌ POLICY DIVERGENCE` marker in the report, directly satisfying "failure output clearly identifies
policy divergence" -- on top of (not instead of) the JUnit assertion failure itself, which is the
real pass/fail signal.

### No real personal data, deterministic inputs

Every scenario's account reference is a generated, synthetic string under the `.test` TLD (e.g.
`scenario-credential-stuffing-<uuid>@example.test`) -- never a real email or identifier, consistent
with `CLAUDE.md`'s explicit constraint. Every input (signal values, event type) is a fixed literal
per scenario, not randomly generated -- deterministic and reproducible on every run, unlike issue
#53's property-based tests, which is the right call here: a scenario's whole point is to
demonstrate one specific, understood attack pattern precisely, not explore an input space.

### Runnable individually or as a suite; no new tooling

Each scenario is its own `@Test` method in one `@SpringBootTest` class -- runnable individually via
ordinary JUnit test selection (`-Dtest=AccountTakeoverScenarioLabTest#credentialStuffing_...`) or
as the whole suite, with zero new test-running infrastructure.

## Alternatives considered

- **Inventing signals for SIM swap/session replay/insider misuse to force all 9 scenarios to
  "work"** -- rejected: would silently misrepresent what this platform's current risk engine
  actually evaluates. Naming the gap honestly is more valuable than a scenario that doesn't
  correspond to any real signal path.
- **A single-account proxy for password spraying** -- considered (e.g. one account with a few
  failed attempts, framed as "one victim in a spray campaign"), then rejected: it would not
  actually demonstrate the *spray* pattern (many accounts, few attempts each) at all, only ordinary
  low-signal traffic -- a misleading scenario is worse than an explicitly deferred one.
- **A new dashboard/visualization for scenario results** -- rejected: the Markdown-report-as-CI-
  artifact convention already established by ADR 0031/ADR 0029 is reused instead of inventing a
  new reporting mechanism for one issue.

## Consequences

### Positive

- every implemented scenario's expected values are independently verifiable against the real
  scoring formula and real policy thresholds cited in this ADR, not asserted against a description
  of intended behavior;
- the MFA fatigue scenario exercises a real cross-module interaction (protection decision ->
  challenge issuance -> challenge exhaustion), not just the risk-scoring step;
- the evidence-bundle integration is a concrete, working link to issue #42's infrastructure, not a
  placeholder.

### Negative

- 4 of the issue's 9 named scenarios are not implemented -- explicitly deferred with a specific,
  named signal-model gap for each, not silently dropped;
- the report is a single Markdown file, not an interactive dashboard; "feed demo dashboards" is
  satisfied as a reusable data artifact, not a rendered UI.

## Guardrails

- All 5 scenario tests and both `scenarios` support classes were `./mvnw test-compile`-verified
  locally; every expected score/outcome was hand-computed and cross-checked against
  `DeterministicRiskAssessmentService`'s source and the real seeded policy thresholds before being
  written into the test.
- Each scenario's JUnit assertions (score, reason codes, outcome, and for MFA fatigue, the
  challenge's final status) are the actual correctness signal; the Markdown report is a
  human-readable artifact alongside them, not a replacement for them.
- All 5 require real Postgres (Testcontainers) to execute -- unavailable in this environment this
  session; CI is the first real execution.

## Revisit criteria

- **SIM swap**: revisit once this system ingests any telecom/carrier-change signal.
- **Session replay**: revisit once inbound session-token reuse detection exists (distinct from the
  existing outbound webhook replay protection, ADR 0026).
- **Insider misuse**: revisit once an internal-actor/employee-access context is modeled anywhere in
  this system.
- **Password spraying**: revisit once a cross-account correlation view exists that a single-decision
  scenario could meaningfully exercise.
- if a real interactive dashboard is ever built for demo purposes, wire it to consume the same
  Markdown/data this report already produces rather than duplicating the scenario logic.

## Links

- Issue #54
- [ADR 0028](0028-signed-redacted-decision-evidence-bundles.md) (the evidence bundle export/verify
  mechanism every scenario exercises)
- [ADR 0031](0031-ci-and-software-supply-chain-security.md) (the report-as-CI-artifact convention
  reused here)
- Tests: `AccountTakeoverScenarioLabTest`, `ScenarioReport`, `ScenarioReportCollector`
