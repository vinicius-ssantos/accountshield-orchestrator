# ADR 0038: Scenario CLI

- Status: Accepted
- Date: 2026-07-28

## Context

Issue #56 asked for a CLI with six commands (`scenario list/run/report`, `policy lint/diff`,
`evidence verify`), using the public API/SDK only, supporting local Docker Compose defaults,
emitting human-readable and JSON output, preserving correlation IDs, redacting sensitive values,
returning meaningful exit codes, and generating Markdown reports; releases should publish
platform-specific binaries or an executable JAR, and demo instructions should use the CLI as the
primary walkthrough.

roadmap.md's Gate 8 exit criterion for this issue is narrower than the full deliverable list:
"CLI executes deterministic scenarios and emits stable JSON/Markdown reports." Only the `scenario`
commands are gate-mandated; `policy lint`/`policy diff`/`evidence verify` are real, buildable
capabilities this ADR still implements fully (every one has a genuine backing API), just without
the same depth of report generation the `scenario` commands get.

## Decision

### A third standalone sibling module, built entirely on the SDK

`cli/` is a fourth standalone Maven project (own `pom.xml`, no parent/reactor relationship),
depending only on `accountshield-sdk` -- the same structural pattern issue #55 established for
`sdk/` and `demo/`. Every command is implemented against `AccountShieldClient`; none touches any
`io.github.viniciusssantos.accountshield.*` server package.

### Picocli, not hand-rolled argument parsing

Unlike the SDK (which deliberately avoided a codegen dependency for its 3-endpoint surface), the
CLI genuinely benefits from a real command-line framework: 6 commands across 3 groups, `--help`/
`--version`, typed positional/option parsing, and this issue's own explicit exit-code requirement
all being exactly what a framework buys back. [picocli](https://picocli.info/) 4.7.7 is a single,
well-established, zero-transitive-bloat dependency for this -- a proportionate choice here, unlike
OpenAPI-codegen would have been for the SDK.

### `scenario` commands: the gate-mandated, fully-built deliverable

`ScenarioDefinition` reuses the exact 5 scenarios and hand-verified expected score/outcome/reason-
codes from issue #54's scenario lab (ADR 0034) verbatim -- not re-derived, so the CLI and the
server-side scenario lab test can never quietly drift apart. `scenario run <name>`:

1. submits the real decision through `AccountShieldClient.decideProtection`;
2. follows up exactly like `accountshield-demo` does: verifies a deliberately wrong code for
   `REQUIRE_STEP_UP`, initiates recovery for `START_RECOVERY`;
3. prints decision and event provenance (issue #56's own wording) -- decision ID, protection
   request ID, policy key/version, algorithm version, score, reason codes, correlation ID;
4. persists a stable `ScenarioRunResult` JSON to `--output-dir` (default
   `~/.accountshield-cli/runs`), keyed by a fresh run ID;
5. exits `0` if the actual outcome/score matched the scenario's expected values, `2` if it
   diverged, `1` on any execution error.

`scenario report <run-id>` reads that persisted JSON back and renders it as Markdown (issue #56:
"generate Markdown reports") or re-prints the raw JSON (`--json`) -- the exact same stable shape
`scenario run --json` already printed, so there is only one JSON schema for this command family to
document, not two.

### `policy diff`: adapted to the real API shape, not the issue's literal wording

Issue #56 named `policy diff <stable> <candidate>`. The real backing endpoint
(`POST /api/v1/simulation/policy-impact`, issue #35/ADR 0021) has no explicit "stable version"
parameter: it replays recent historical decision traces for a policy key and compares each one's
*actually-recorded* outcome (whichever policy version really produced it, which may vary trace to
trace) against what a single candidate version would produce. There is no second "stable" version
to name. The command was adapted to the real shape: `policy diff <policy-key> <candidate-version>`
-- building a fictional "stable version" parameter the server has no use for would have produced a
CLI that lies about what it does. This is stated here explicitly rather than silently diverging
from the issue text.

### `evidence verify`: pass-through, not a local port

`AccountShieldClient.verifyEvidenceBundle(String rawBundleJson, ...)` takes the bundle file's raw
JSON text and sends it to `POST /api/v1/evidence/verify` byte-for-byte unmodified -- it does not
parse the bundle into a typed `EvidenceBundle`/`EvidenceManifest`/`EvidenceBundleContent` model at
all. Investigated a fully-offline local port (mirroring issue #55's `WebhookSignatureVerifier`
pattern, since `EvidenceBundleService.verify()` is genuinely a pure, self-contained computation --
SHA-256 content hash plus `SHA256withRSA` signature check against a public key embedded in the
bundle itself, no database access): rejected because the hash step is only reproducible if a local
reimplementation serializes `EvidenceBundleContent` to *byte-identical* JSON as the server's exact
configured `ObjectMapper`, and that exact configuration was not confirmed reproducible with
confidence. A subtly-wrong local reserialization would be a uniquely bad kind of bug for a security
verification tool: it could silently report a tampered bundle as valid, or a genuine bundle as
invalid, and nothing about the CLI's own output would reveal which one happened. Sending the exact
original bytes to the server that issued the bundle removes that entire risk class. Every other
command in this CLI is genuinely safe to retry (side-effect-free analysis); this one's request body
is also never inspected or transformed at any point, which is the load-bearing property.

### Exit codes and JSON stability

A three-value contract (documented in `cli/README.md` and ADR, not just code comments): `0` success
+ condition held, `1` execution error, `2` command succeeded but the checked condition failed
(divergence/lint-error/threshold-exceeded/invalid-bundle). `--json` output for every command is
exactly the underlying SDK model's Jackson serialization -- no CLI-specific reshaping -- so the
schema is inherently as stable as the SDK's own models (`sdk/README.md`'s existing stability
statement extends to the CLI's JSON output for free).

### Redaction relies on existing server-side guarantees, not new client-side logic

Issue #56 asks the CLI to "redact sensitive values." `ProtectionDecisionResponse` never carries a
raw account reference at all (confirmed from the DTO's field list), and `PolicyImpactReport`'s
`DivergentDecision.redactedAccountReference` is already redacted server-side before it ever reaches
this CLI. No client-side redaction logic was added, since there is no raw sensitive value in any
response this CLI ever sees to redact.

### Distribution: an executable JAR only, native binaries deferred

`maven-assembly-plugin` (`jar-with-dependencies`, same pattern as `demo/pom.xml`) produces a single
runnable `accountshield-cli.jar`, satisfying "an executable JAR." Platform-specific native binaries
(GraalVM native-image, cross-compiled for macOS/Linux/Windows) are deferred: this repository has no
tagged-release pipeline yet at all (issue #28's job, repeatedly noted as blocking real release
artifacts throughout this session's ADRs), so building multi-platform native binaries now would
have no release process to actually publish them through.

### CI: build in `verify` and `nightly`, proven end to end by a real subprocess

`ci.yml`'s `verify` job and `nightly.yml`'s `full-verify` job both now build `accountshield-cli`
(assembling the jar, not just compiling) before running the main test suite, because
`CliEndToEndTest` (server-side, mirroring `SdkContractVerificationTest`'s pattern) runs the real,
already-assembled jar as a genuine `ProcessBuilder` subprocess against that test's own live,
random-port server instance, asserting on the real process exit code and real stdout JSON for
`scenario run credential-stuffing`. This directly satisfies "CLI can execute at least the initial
adversarial scenarios" with a real, executed proof -- not a description of intended behavior.
Unlike `accountshield-demo`, no *additional* end-to-end check was added to `ci.yml`'s `docker` job:
`CliEndToEndTest` already proves the CLI's core flow for real, and a second proof against a Docker
container instead of a Testcontainers instance would be redundant coverage of the same claim for
meaningfully more CI time, not a new one.

## Alternatives considered

- **Hand-rolled argument parsing (matching the SDK's "no unnecessary dependency" stance)** --
  rejected: 6 commands across 3 groups with typed options/positionals and a real exit-code contract
  is exactly the point at which a small, well-established CLI framework earns its keep; the SDK's
  reasoning for avoiding OpenAPI-codegen doesn't transfer to a materially different kind of module.
- **Implementing `policy diff` as issue #56 literally specifies (two explicit version arguments)**
  -- rejected: would require either inventing a second server endpoint this codebase doesn't have,
  or silently ignoring one of the two arguments; adapting the command to the real, existing API
  shape is more honest than building a command that pretends to do something the backend can't.
  See the dedicated section above.
- **A fully-offline `evidence verify`** -- rejected due to the Jackson-serialization-reproducibility
  risk described above; the online pass-through is simpler, safer, and still genuinely uses "the
  public API/SDK only" as the issue's scope explicitly asks for.
- **Native binaries via GraalVM native-image now** -- rejected: no release pipeline exists yet to
  publish them through (issue #28).

## Consequences

### Positive

- every command genuinely calls a real backing API, proven either by SDK-level unit tests (the
  request/response shape) or `CliEndToEndTest` (`scenario run`'s full real flow against a live
  server);
- the exit-code and JSON-schema contracts are simple, uniform across all 6 commands, and documented
  in one place (`cli/README.md`);
- `evidence verify`'s pass-through design eliminates an entire class of "subtly wrong local
  reimplementation" risk for a security-verification command.

### Negative

- `policy diff`'s command signature deviates from issue #56's literal wording (no second "stable
  version" argument) -- documented explicitly here and in `cli/README.md` rather than silently;
- only an executable JAR is distributed; platform-specific native binaries remain unbuilt until a
  release pipeline exists;
- `policy lint`/`policy diff`/`evidence verify` do not have their own dedicated
  `CliEndToEndTest`-style live-subprocess proof (only `scenario run` does) -- their correctness
  rests on the SDK-level HTTP-fake unit tests proving the client-side request/response handling,
  not a full CLI-process-level live-server round trip. Proportionate given these three are not
  gate-mandated, but noted as a real, current gap.

## Revisit criteria

- If a release pipeline is ever built (issue #28), revisit native-image binaries for `cli/`.
- If `policy lint`/`policy diff`/`evidence verify` become gate-mandated or otherwise load-bearing,
  add `CliEndToEndTest`-style live-subprocess coverage for them too, matching `scenario run`'s.
- If issue #56's literal `policy diff <stable> <candidate>` two-version signature is ever actually
  needed, it would require a new server-side endpoint accepting two explicit policy versions to
  replay against a shared, explicit historical trace set -- currently out of scope.

## Links

- Issue #56
- [ADR 0034](0034-adversarial-account-takeover-scenario-lab.md) (the exact scenario math
  `ScenarioDefinition` reuses)
- [ADR 0037](0037-java-client-sdk-and-demo.md) (the SDK this CLI is built entirely on, and the
  standalone-sibling-module pattern this ADR reuses)
- [ADR 0021](0021-historical-policy-impact-analysis.md) (the policy-impact analysis `policy diff`
  wraps)
- [ADR 0028](0028-signed-redacted-decision-evidence-bundles.md) (the evidence bundle format
  `evidence verify` passes through unmodified)
- Code: `cli/`, `src/test/java/.../cli/CliEndToEndTest.java`
