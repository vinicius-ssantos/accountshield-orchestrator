# ADR 0039: Repository governance and reproducible release

- Status: Accepted
- Date: 2026-07-28

## Context

Issue #28 named a broad set of governance and release gaps: repository status disagreeing with
issue status, a stale delivery Epic, no branch protection or required checks, no PR/issue
templates or CODEOWNERS, no formal commit convention or changelog, and no tagged, reproducible
release with a demo package (Postman/curl, seed data, architecture diagrams, Grafana screenshots,
an interview script). This is the final issue in this project's original backend-hardening
sequence (docs/roadmap.md Gate 9); every other issue in that sequence is closed.

## Decision

### A real, verified reconciliation of issue status -- not just documentation

Before writing anything new, every issue referenced by Epic #15's backlog was checked against its
real merge commit on `main` (`git log --grep`), not assumed from memory. This surfaced a genuine,
significant gap: **23 issues with real, merged work were still showing as OPEN on GitHub**, because
their PR bodies referenced the issue only as a bare `#N` (usually in the PR title), which GitHub
does not treat as an auto-closing keyword -- only `Closes #N`/`Fixes #N`/`Resolves #N` in the PR
*body* auto-close an issue on merge. All 23 were closed here, each with a comment naming the exact
PR that completed it. `CONTRIBUTING.md` now states this explicitly (use `Closes #N` in the PR
body, not just a title reference) so it does not recur.

Epic #15 was updated (not closed) to check off every genuinely completed item, with an explicit
status comment distinguishing this epic's real remaining scope (#28 itself) from #41 (the operator
console), which is intentionally tracked under its own separate, larger epic and was never really
part of this epic's backend-hardening core.

### README's "Current delivery status" was rewritten, not patched

The existing section claimed authentication/RBAC, signed webhooks, audit hash chaining, distributed
tracing, and the SDK/CLI were all "not yet delivered" -- every one of them had already shipped.
This is precisely the "repository status and issue status agree" acceptance criterion issue #28
names, so it was rewritten wholesale against the real feature catalog rather than incrementally
patched. The illustrative decision-response JSON example was also replaced: the old one used
fictional field names (`decision`, `riskLevel`, `requiredChallenge`) that never matched the real
`ProtectionDecisionResponse` shape -- the new example is the real, current response shape, produced
by `docs/demo/curl-walkthrough.md`'s own first command.

### Governance files: templates, CODEOWNERS, Conventional Commits, changelog

- `.github/pull_request_template.md` and `.github/ISSUE_TEMPLATE/*` (GitHub issue forms) --
  standard, low-risk additions.
- `.github/CODEOWNERS`: this is a single-maintainer project today, so the file's main value is
  documenting *intent* (which paths carry outsized security/correctness weight -- crypto, audit,
  migrations, ADRs, CI workflows) rather than routing real review assignment across people.
- `CONTRIBUTING.md` documents the Conventional Commits convention this repository's history
  already follows in practice (93% of all commits already match `feat:`/`fix:`/`test:`/etc. before
  this ADR) -- formalizing an existing, working convention rather than imposing a new one.
- `CHANGELOG.md` (Keep a Changelog format) was generated from real, merged commit history, grouped
  by theme under one `[Unreleased]` heading (no release exists yet) -- not hand-waved as "will
  document later."

### Release: a real, verified-to-not-break-anything workflow, but no tag cut yet

`.github/workflows/release.yml` (triggered only by a `v*.*.*` tag push, never by an ordinary
push/PR) builds `sdk`/`demo`/`cli`, runs the server's own `mvn verify` (producing the CycloneDX
SBOM already wired since ADR 0031), builds and pushes the Docker image to GHCR
(`ghcr.io/<owner>/<repo>:<tag>` and `:latest`), and creates a GitHub Release attaching the SBOM,
changelog, and every module's jar via `gh release create` (matching this session's established
`gh` CLI usage rather than adding a new marketplace action for release-asset upload).
`docs/RELEASING.md` documents the manual steps (updating the changelog heading, tagging, pushing)
that trigger it.

**Deliberately not executed as part of this PR**: creating the `v1.0.0` tag itself, and enabling
branch protection on `main`. Both are real, externally-visible, comparatively hard-to-reverse
actions (a public tagged release plus a public container image; a repository setting that changes
how every future push to `main` behaves) -- qualitatively different from every other action this
session's standing autonomous-cycle instruction covers (opening a PR, merging after green CI). They
are confirmed with the repository owner separately, after this PR merges, per this project's
"check before hard-to-reverse, externally-visible actions" operating principle.

### Demo package: real artifacts, one explicit exception

- `docs/demo/curl-walkthrough.md`: real curl commands, exact current request/response shapes,
  covering all seven areas issue #28 names (decision, challenge, recovery, replay, policy
  rollout/impact, audit, outbox).
- `docs/postman/AccountShield.postman_collection.json`: a real, schema-valid Postman v2.1
  collection covering the same seven areas plus evidence export, with a token-capture test script
  so the collection is runnable end to end without manual variable copying.
- `scripts/seed-demo-data.sh`: seeds representative data by running every named scenario through
  the Scenario CLI (issue #56) rather than hand-written seed SQL that would need to be kept in
  sync with the schema separately.
- `docs/demo/interview-script.md`: a ~10-minute narrated walkthrough covering the same seven areas,
  explicitly framing the project's non-production-readiness per `SECURITY.md` up front.
- Architecture diagrams: `docs/architecture/README.md` and `docs/architecture/recovery.md` already
  contain real Mermaid diagrams from earlier work -- no new diagram was added, since the existing
  ones already satisfy this item.
- **Grafana screenshots were not captured**: this requires a live-running Compose stack (Grafana +
  Prometheus + the app serving real traffic), and Docker was unavailable in this environment for
  this issue. `docs/RELEASING.md`/this ADR name the exact reproduction steps
  (`docker compose up -d`, open `http://localhost:3000`, screenshot the pre-provisioned dashboard)
  rather than fabricating an image -- a fabricated screenshot would be worse than an honestly
  documented gap.

## Alternatives considered

- **Closing Epic #15 outright** -- rejected: #41 (operator console) remains genuinely open under
  its own separate epic; closing #15 while one of its own listed checklist items is still open
  would misrepresent status, exactly what this issue warns against.
- **A changelog-generation tool (git-cliff, release-please, etc.)** -- rejected for now: this
  repository's commit history is not yet 100% Conventional-Commits-clean (a handful of very early,
  pre-convention commits exist), and a hand-curated first changelog is more accurate than a
  mechanically-generated one that would need manual cleanup regardless. Revisit once every commit
  going forward is guaranteed convention-compliant.
- **`softprops/action-gh-release` or similar for release-asset upload** -- rejected in favor of
  `gh release create`, already the standard tool this session used for every PR/issue interaction;
  no new marketplace-action dependency needed.
- **Fabricating a Grafana screenshot or diagram from memory** -- rejected outright; see above.

## Consequences

### Positive

- repository issue status now genuinely agrees with what is merged on `main` -- verified against
  real merge commits, not assumed;
- the README no longer misrepresents nearly the entire hardening backlog as undelivered;
- a real, working release pipeline exists and is provably wired correctly (this PR's own CI proves
  the non-tag-triggered jobs still pass), ready to run the moment a tag is pushed;
- the demo package gives a reviewer a genuine, reproducible way to exercise every major capability
  without reading source code.

### Negative

- no `v1.0.0` tag exists yet; the release workflow is unverified in its actual triggered form until
  a tag is pushed (deliberately deferred, see above);
- branch protection is not yet enabled -- `main` remains pushable without a required PR review
  until that setting is applied;
- Grafana screenshots remain a named, undelivered item pending a live-running stack.

## Revisit criteria

- Once `git tag v1.0.0` is pushed (with the repository owner's explicit go-ahead), verify
  `release.yml` end to end: image pullable from GHCR, release page has all five artifacts, update
  `SECURITY.md`'s "Supported versions" line to name the release.
- Once branch protection is enabled, update this ADR's Consequences section to reflect it.
- Once Docker is available in a working session, capture real Grafana screenshots and add them
  under `docs/demo/screenshots/`.
- If commit history becomes 100% Conventional-Commits-compliant going forward, revisit automated
  changelog generation.

## Links

- Issue #28
- Epic #15 (updated, not closed -- see its own status comment)
- [ADR 0031](0031-ci-and-software-supply-chain-security.md) (the CycloneDX SBOM this release
  workflow reuses)
- `CONTRIBUTING.md`, `CHANGELOG.md`, `docs/RELEASING.md`, `docs/demo/`, `docs/postman/`,
  `scripts/seed-demo-data.sh`
