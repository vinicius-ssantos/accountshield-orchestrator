# Releasing

## Prerequisites

- `main` is green (CI, CodeQL, and Trivy/Gitleaks scans all passing on the latest commit).
- `CHANGELOG.md`'s `[Unreleased]` section accurately reflects everything since the last release --
  rename it to `[X.Y.Z] - YYYY-MM-DD` and start a fresh `[Unreleased]` heading above it.
- `docs/features/README.md`, `docs/adr/README.md`, and `docs/roadmap.md` agree with reality (see
  `CONTRIBUTING.md`'s "Roadmap maintenance" rule) -- a release should never publish a claim that
  contradicts the feature catalog or `SECURITY.md`.

## Production secrets

The `production` Spring profile activates `ProductionSecretsGuard`, which refuses to boot if any
configured secret is still at its `accountshield-local-only-` default. The AES-256 key-encryption
key (ADR 0025) and the webhook secret-encryption key must be base64-encoded material that decodes
to exactly 32 bytes -- generate each independently:

```bash
openssl rand -base64 32   # ACCOUNTSHIELD_CRYPTO_ACTIVE_KEK_SECRET
openssl rand -base64 32   # ACCOUNTSHIELD_WEBHOOK_SECRET_ENCRYPTION_KEY
```

The HMAC and pseudonym secrets (`CHALLENGE_HMAC_SECRET`, `ACCOUNT_PSEUDONYM_SECRET`,
`ACCOUNTSHIELD_CRYPTO_SUBJECT_ID_SECRET`) accept arbitrary high-entropy strings; generate them
with `openssl rand -hex 32` or equivalent. Never reuse a key across purposes.

## Cutting the release

```bash
git checkout main && git pull
# Update CHANGELOG.md as above, commit it directly to main (or via a small PR).
git tag -a v1.0.0 -m "AccountShield v1.0.0"
git push origin v1.0.0
```

Pushing the tag triggers `.github/workflows/release.yml`, which:

1. builds `sdk/`, `demo/`, and `cli/` (standalone Maven projects, no reactor);
2. runs the server's own `mvn verify` (produces the CycloneDX SBOM, `target/bom.xml`/`bom.json`);
3. builds and pushes the Docker image to `ghcr.io/<owner>/<repo>:<tag>` and `:latest`;
4. creates a GitHub Release (`gh release create`) attaching the SBOM, `CHANGELOG.md`, the
   `cli`/`demo`/`sdk` jars, and the Postman collection.

## After the release

- Update `SECURITY.md`'s "Supported versions" section to name the released version explicitly
  (it currently says "until the first tagged release, only the latest commit on `main`").
- Verify the GHCR image is reachable: `docker pull ghcr.io/<owner>/<repo>:v1.0.0`.
- Verify the GitHub Release page lists all five artifacts and that `gh release view v1.0.0` shows
  a `Published` (not draft) release.

## First release: branch protection

Before the first tag, confirm branch protection is enabled on `main` (required PR reviews, the
`Maven verify`/`CodeQL`/`Dependency review` status checks required, direct pushes blocked) -- see
issue #28 and ADR 0039 for what was configured and why. This is a one-time repository setting, not
part of the release workflow itself.
