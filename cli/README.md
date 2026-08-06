# AccountShield Scenario CLI

Command-line tool for running deterministic adversarial scenarios, linting/diffing policies, and
verifying evidence bundles against a running AccountShield instance -- built entirely on
[`accountshield-sdk`](../sdk/README.md) (issue #55), no dependency on any server-internal package.
See `docs/adr/0038-scenario-cli.md` for the full design and scoping rationale.

## Install

```bash
cd sdk && mvn install -DskipTests
cd ../cli && mvn package
```

Produces `target/accountshield-cli.jar`, an executable fat jar:

```bash
java -jar target/accountshield-cli.jar --help
```

**IDE note**: `sdk/`, `demo/`, and `cli/` are standalone Maven projects (no reactor with the root
`pom.xml`) -- opening just the repo root will not resolve `picocli` or any `accountshieldcli.*`
class. Import `cli/pom.xml` (and `sdk/pom.xml`, `demo/pom.xml`) as their own Maven projects in your
IDE (IntelliJ: Maven tool window -> `+` -> select the `pom.xml`), and make sure `sdk` has been
`mvn install`ed locally first, since `cli` resolves it from your local `~/.m2` repository, not a
reactor reference.

## Global options

Every command accepts:

| Option | Default |
|---|---|
| `--base-url <url>` | `$ACCOUNTSHIELD_BASE_URL` env var, or `http://localhost:8080` |
| `--token <jwt>` | `$ACCOUNTSHIELD_BEARER_TOKEN` env var |
| `--correlation-id <id>` | a random one is generated and printed |
| `--json` | emit machine-readable JSON instead of human-readable text |

Every endpoint this CLI calls sits behind AccountShield's JWT resource server (ADR 0011):
`scenario run`/`policy lint` need `PROTECTION_CLIENT`/`POLICY_ADMIN` respectively, `policy diff`
needs `SIMULATION_ANALYST`, `evidence verify` needs `SECURITY_OPERATOR`. Obtain a token from your
identity provider integration, or (local/demo instances only) the server's `local`-profile-only
`POST /dev/tokens` endpoint.

## Commands

### `scenario list`

Lists the 5 deterministic adversarial scenarios (the exact, hand-verified scenarios from issue #54's
scenario lab, ADR 0034): `credential-stuffing`, `impossible-travel`, `device-takeover`,
`mfa-fatigue`, `recovery-abuse`.

### `scenario run <name>`

Runs one named scenario against a live instance: submits the real protection decision, follows up
on `REQUIRE_STEP_UP` (verifies a deliberately wrong code) or `START_RECOVERY` (initiates recovery),
prints decision and event provenance (decision ID, protection request ID, policy key/version,
algorithm version, score, reason codes, correlation ID), and persists the result to
`--output-dir` (default `~/.accountshield-cli/runs`) as `<run-id>.json` for `scenario report`.

```bash
java -jar target/accountshield-cli.jar scenario run credential-stuffing --token "$TOKEN"
```

### `scenario report <run-id>`

Renders a Markdown report from a previously-persisted run result (`--out <file>` to write to a
file instead of stdout; `--json` to print the raw persisted JSON instead).

```bash
java -jar target/accountshield-cli.jar scenario report <run-id> --out report.md
```

### `policy lint <file>`

Statically analyzes a candidate policy threshold set from a local JSON file --
`{"allowMaxScore": 29, "stepUpMaxScore": 69, "recoveryMaxScore": 89}` -- without creating a draft
policy. Any omitted/null field triggers a `*_MISSING` diagnostic.

### `policy diff <policy-key> <candidate-version>`

Replays recent historical decisions recorded for `<policy-key>` and compares each one's
*actually-recorded* outcome against what `<candidate-version>` would have produced. There is no
separate "stable version" argument -- each historical trace is compared against whatever policy
version actually produced it at the time (see ADR 0038 for why this command's shape follows the
real API rather than the literal `policy diff <stable> <candidate>` wording). `--max-samples`
(default 5000) bounds how many historical decisions are sampled.

### `evidence verify <bundle-file>`

Verifies a previously-exported evidence bundle JSON file. The file's raw bytes are sent to the
server unmodified -- this command never parses or reconstructs the bundle internally.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success, and the checked condition held (scenario matched its expected outcome, no lint errors, impact within threshold, bundle valid) |
| `1` | Execution error: network/HTTP failure, unknown scenario name, missing file |
| `2` | The command ran successfully but the checked condition failed: scenario diverged, a lint `ERROR` diagnostic was found, impact exceeded its divergence threshold, or the evidence bundle is invalid |

## JSON output

`--json` output is exactly the same Jackson-serialized shape as the underlying SDK model
(`ProtectionDecisionResponse`-derived `ScenarioRunResult`, `PolicyAnalysisResult`,
`PolicyImpactReport`, `EvidenceVerificationResult`) -- field names and types are stable and will
not change without a corresponding SDK model change (see `sdk/README.md`).
