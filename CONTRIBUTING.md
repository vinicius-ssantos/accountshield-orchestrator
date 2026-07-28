# Contributing

## Before you start

Read `README.md`, `docs/architecture/README.md`, the accepted ADRs in `docs/adr/`, `SECURITY.md`,
and `docs/roadmap.md`. `AGENTS.md`/`CLAUDE.md` is the canonical instruction set for this repository
(including for AI coding agents) -- its rules apply to every contributor.

## Commit messages: Conventional Commits

This repository's history already follows [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`, `perf:`, `ci:`, `build:`, optionally
scoped like `fix(policy): ...`) -- `CHANGELOG.md` is generated from this convention, so keep using
it:

```text
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

- `feat:` a new capability;
- `fix:` a bug fix;
- `test:` adding or correcting tests with no production behavior change;
- `docs:` documentation only;
- `chore:` tooling/dependency maintenance with no behavior change;
- `refactor:`/`perf:` internal restructuring or performance work with no behavior change;
- `ci:`/`build:` CI or build-tooling changes.

## Pull requests

- Use the PR template (`.github/pull_request_template.md`).
- Reference the issue with `Closes #N` in the PR body, not just `#N` in the title -- GitHub only
  auto-closes an issue on merge when the body uses a recognized closing keyword.
- State explicitly which acceptance criteria are addressed and which are deferred; do not let a PR
  imply more than it delivers.
- State what was actually verified locally versus left to CI (e.g. "Docker unavailable in this
  environment; CI is the first real execution") -- see `SECURITY.md`'s and every ADR's "state
  assumptions clearly" standard.
- Follow `docs/roadmap.md`'s "Roadmap maintenance" rule: update the feature catalog
  (`docs/features/README.md`), the relevant architecture page, an ADR, and the roadmap/parent Epic
  in the same PR that changes status -- not as a follow-up.

## When a change needs an ADR

See `docs/adr/README.md`'s "When a new ADR is required" and "Required ADR structure" sections.

## Engineering constraints

- Do not introduce microservices, Kafka, machine learning, or real challenge providers without an
  accepted issue and ADR.
- Never use real credentials, authentication secrets, personal data, or production security events
  in examples or tests -- synthetic `.test`/`.example` fixtures only.
- Run `./mvnw --batch-mode --no-transfer-progress verify` before opening a PR. `sdk/`, `demo/`, and
  `cli/` are standalone Maven projects (no reactor relationship to the root `pom.xml`); build and
  install `sdk/` first if you touch any of the three (`cd sdk && mvn install`).

## Reporting a vulnerability

Do not open a public issue for a security vulnerability -- see `SECURITY.md`.
