# CLAUDE.md

Follow `AGENTS.md` as the canonical repository instruction set.

Before editing, read `README.md`, `docs/architecture/README.md`, the accepted ADRs in `docs/adr`, and `SECURITY.md`.

## Project-specific reminders

- This is an account-protection decision and orchestration platform, not an identity provider.
- Preserve explainability, immutability, deterministic replay, idempotency, and explicit state transitions.
- Keep Spring Modulith boundaries intentional and run `mvn --batch-mode --no-transfer-progress verify` after code changes.
- Do not introduce microservices, Kafka, machine learning, or real challenge providers without an accepted issue and ADR.
- Never use real credentials, authentication secrets, personal data, or production security events in examples or tests.
- State assumptions and incomplete verification clearly in pull requests.
