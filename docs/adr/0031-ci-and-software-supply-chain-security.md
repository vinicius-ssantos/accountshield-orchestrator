# ADR 0031: CI and software-supply-chain security gates

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #27 asked for a broad set of CI quality/security gates: JaCoCo coverage thresholds, SpotBugs,
Checkstyle, CodeQL, dependency review, Dependabot/Renovate, CycloneDX SBOM, Trivy filesystem/image
scans, secret scanning, a container smoke test, test/coverage report artifacts, and pinned
action/container versions. `docs/roadmap.md`'s Gate 9 restates the same list as "Deliverables"
rather than giving a narrower "Gate exit criteria" subsection the way earlier gates did, so this
ADR had to make its own proportionality call rather than defer to a stated minimum bar.

A significant environment discovery changed how this issue was approached partway through: earlier
issues this session (#24 in particular) treated "this environment cannot run Maven" as a fixed
constraint, based on a bare `mvn -version` failing. Investigating this issue found the repository
actually ships a working Maven wrapper (`./mvnw`), which **does** run locally and can compile,
package, and run individual plugin goals -- it was simply never tried. This was used throughout
this issue's implementation to verify every new Maven plugin (JaCoCo, CycloneDX) actually resolves
and runs correctly *before* pushing, which directly avoids repeating #24's two-round CI-failure
pattern (a missing-version dependency, then a transitive Kotlin-reflect crash) for this issue's own
new plugins. Docker's CLI is also installed, but the daemon is not running in this environment, so
Testcontainers-backed `mvn verify` and any real `docker build`/`docker run` still could not be
exercised locally -- only the Maven-plugin-level pieces could be pre-verified this way.

## Decision

### Verified locally wherever `./mvnw` allows it

`jacoco-maven-plugin:0.8.12` and `cyclonedx-maven-plugin:2.9.1` were both added to `pom.xml` and
then actually run locally (`./mvnw ... test-compile`, `-DskipTests package`, and the CycloneDX goal
directly) before being pushed -- confirming both resolve, execute, and produce real output
(a 155-component SBOM; JaCoCo cleanly no-ops when no test execution data exists yet, e.g. under
`-DskipTests`, rather than failing). This is a direct, deliberate response to this session's own
recent experience: guessing at plugin/dependency configuration and finding out only via a CI push
is expensive; every piece of this PR that touches `pom.xml` was checked locally first.

### Coverage and SBOM: report first, gate later

**No coverage threshold is enforced yet.** This codebase has never had a JaCoCo report generated
before (confirmed: `pom.xml` had no coverage plugin at all), so there is no real line/branch
coverage number to know if issue #27's suggested 80%/70% starting thresholds are even achievable
without first seeing where the codebase actually stands. Setting a blind threshold risked an
immediate, uninformative CI failure -- the same category of mistake as guessing an OpenAPI baseline
by hand in issue #52. Instead, `jacoco-maven-plugin` generates a real HTML/XML report every run,
uploaded as a CI artifact; a follow-up PR should read that report's real numbers and phase in
thresholds (starting with the stricter per-module ones issue #27 names for recovery, challenge,
policy, idempotency, and outbox) deliberately, not blindly. The same reasoning applies to the
CycloneDX SBOM: it's generated and uploaded as a build artifact on every run, but not yet attached
to a tagged GitHub Release, since (as in ADR 0029) this repository has never cut one.

### Dependency review: a hard gate is safe here, unlike coverage

`actions/dependency-review-action` (new `dependency-review.yml`, PR-only) is configured with
`fail-on-severity: high` as an **actual blocking gate from day one** -- unlike coverage or lint
thresholds, this needs no historical baseline to be safe: it only evaluates dependencies *newly
introduced in the PR's diff* against known vulnerability severity, so there is no "existing debt"
it could unfairly fail on. This directly satisfies "pull requests fail on new critical/high
dependency vulnerabilities."

### Trivy and Gitleaks: advisory first, for the opposite reason coverage is deferred

Trivy's filesystem scan (new dependencies) and image scan (the built Docker image) both run with
`exit-code: "0"` -- they upload SARIF to GitHub code scanning (visible, reviewable) but do not fail
the build. Unlike dependency review, these scans cover the **entire existing** dependency tree and
base images, not just a PR's diff -- and since no Trivy scan has ever run against this codebase
before, there is no way to know whether it would immediately report pre-existing findings that
would need triage (the same "unknown baseline" problem coverage thresholds have). Gitleaks is
similarly advisory (`continue-on-error: true`): this codebase deliberately uses many long,
descriptively-named "local-only" placeholder secrets (e.g.
`accountshield-local-only-webhook-secret-key`) as documented-safe defaults, not real credentials,
and whether gitleaks' default entropy/pattern rules false-positive on those could not be verified
without actually running it -- no local Docker daemon was available this session to test the
action, and there's no equivalent of `./mvnw` to dry-run a GitHub Action locally.

### CodeQL: verified locally up to the point CI diverges

`codeql.yml` builds via `mvn -DskipTests package` (verified locally, see above) before CodeQL's own
`analyze` step, which cannot itself be run outside GitHub's infrastructure. This is the officially
recommended CodeQL-for-Java pattern (init, build, analyze) and needs no project-specific tuning.

### A real container smoke test, not a synthetic one

The `docker` job now actually starts the built image against a real Postgres container (plain
`docker network create` + `docker run`, not `docker compose`, so the health check can `curl` from
the GitHub-hosted runner itself -- the app's minimal JRE base image is not guaranteed to have
`curl`/`wget` installed, so exec-ing into the app container to self-check would have been a less
reliable choice) and polls `/actuator/health` for up to 60 seconds, failing the job if it never
reports `UP`. This directly satisfies "image runs successfully in CI after build" -- not merely
"the image builds," which is all the pre-existing `docker` job actually proved before this PR.

### Pinned versions

`compose.yaml`'s three previously-`:latest`-tagged images (`jaegertracing/all-in-one`,
`prom/prometheus`, `grafana/grafana`) were pinned to specific released versions. GitHub Actions
steps throughout this repository's workflows were already pinned to major-version tags (`@v4`,
`@v6`, etc.) before this issue, which is kept as-is rather than moved to exact commit SHAs -- a
larger hardening step with its own maintenance burden (SHA pins need manual updates Dependabot's
default configuration does not automatically handle as smoothly as tag updates), left as a
revisit item rather than bundled into this already-broad PR.

## Alternatives considered

- **SpotBugs and Checkstyle** -- deferred entirely. Both would very likely surface a large number
  of pre-existing findings across this codebase's full size, requiring extensive suppression-rule
  tuning this session cannot do blindly (no local way to run either tool against the whole
  codebase and observe real output first, the same verifiability gap that applies to Trivy/coverage
  but at a scale this ADR judged too large to gamble on for one PR). Explicitly named as the
  highest-priority follow-up.
- **Hard-failing coverage thresholds now, using the issue's suggested 80%/70% numbers** --
  rejected: no real baseline exists; an uninformed threshold could immediately and uninformatively
  fail every future PR.
- **Renovate instead of Dependabot** -- Dependabot chosen: it's natively integrated into GitHub
  (no separate app installation/hosting decision needed) and satisfies "dependency updates arrive
  through reviewable pull requests" with zero additional infrastructure.
- **`docker compose` for the smoke test** -- rejected in favor of plain `docker network`/`docker
  run`: compose would have made the health check depend on either the app image having `curl`/
  `wget` (unverified) or a fragile `--network container:` sharing trick; direct `docker run` with
  a published port lets the GitHub runner curl it directly, which is guaranteed available.

## Consequences

### Positive

- every new Maven-plugin-touching change in this PR was actually executed locally before pushing,
  directly applying the `./mvnw` discovery to avoid repeating issue #24's two-round CI-failure
  pattern;
- a real, safe, no-baseline-needed hard gate (dependency review) exists from day one for the
  literal "PRs fail on new critical/high vulnerabilities" acceptance criterion;
- the Docker image is now proven to actually start and serve traffic in CI, not just build;
- SBOM and coverage reporting mechanisms exist and are exercised every run, ready for real
  thresholds/release-attachment once the underlying "no baseline yet" gaps are closed.

### Negative

- SpotBugs, Checkstyle, and hard-failing coverage thresholds -- three of the issue's explicitly
  named scope items -- are not implemented, only planned as prioritized follow-ups;
- Trivy and Gitleaks are advisory, not blocking, until a clean baseline run demonstrates they don't
  immediately fail on pre-existing, already-accepted state;
- the container smoke test and CodeQL's `analyze` step could not be executed in this environment
  (no running Docker daemon, and CodeQL analysis is GitHub-infrastructure-only) -- CI is the first
  real check for those two specific pieces, unlike everything else in this PR.

## Guardrails

- Locally verified before push: `./mvnw --batch-mode --no-transfer-progress compile`,
  `test-compile`, `-DskipTests package`, and direct invocation of the JaCoCo and CycloneDX plugin
  goals, all producing the expected output (a real SBOM; a report or a clean no-op depending on
  whether test execution data exists).
- CI-only verification (documented, not a gap being hidden): the CodeQL `analyze` step, the
  container smoke test's actual pass/fail behavior, and Trivy/Gitleaks' real findings against this
  codebase.

## Revisit criteria

- once a real JaCoCo coverage number is observed from a CI run, phase in the issue's suggested
  thresholds (80% line / 70% branch, stricter for recovery/challenge/policy/idempotency/outbox) in
  a dedicated follow-up PR;
- once Trivy's filesystem/image scans and Gitleaks have each produced at least one clean run (or a
  triaged, allowlisted set of accepted findings), remove their advisory `exit-code`/
  `continue-on-error` settings and let them gate for real;
- add SpotBugs and Checkstyle in a dedicated follow-up, with enough budget to actually tune
  suppression rules against this codebase's real, current state rather than guessing;
- when the first git tag/GitHub Release is cut, attach the CycloneDX SBOM to it directly (mirrors
  ADR 0029's identical deferred-release-artifact reasoning);
- consider pinning GitHub Actions steps to exact commit SHAs instead of major-version tags, if the
  added maintenance burden is judged worthwhile.

## Links

- Issue #27
- `docs/roadmap.md` Gate 9 (this issue's own deliverables list, used directly since no narrower
  gate-exit-criteria subsection exists for this gate)
- [ADR 0029](0029-api-and-event-compatibility-gates.md) (the identical "bootstrap now, gate later"
  and "no tags yet" reasoning patterns reused here)
- [ADR 0030](0030-transaction-aware-observability-and-tracing.md) (the two CI failures --missing
  plugin version, transitive Kotlin-reflect crash-- that motivated verifying every new plugin
  locally in this issue before pushing)
