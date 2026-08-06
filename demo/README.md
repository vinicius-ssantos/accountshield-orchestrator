# AccountShield SDK Demo

For exploring a running instance from a terminal, the [Scenario CLI](../cli/README.md) (issue #56)
is the primary walkthrough. This demo is the equivalent, fully-programmatic Java example for
consumers integrating the SDK directly into their own application, rather than exploring
interactively.

A realistic Java consumer built entirely on `../sdk` (`accountshield-sdk`) -- no dependency on any
AccountShield server-internal package. Submits three protection decisions covering all three
consumer-visible outcomes (`ALLOW`, `REQUIRE_STEP_UP`, `START_RECOVERY`), handles the step-up and
recovery branches, demonstrates webhook signature verification and replay protection, and prints a
simple event timeline. See `docs/adr/0037-java-client-sdk-and-demo.md` for the full design,
including why the webhook demonstration signs its own sample payload rather than wiring a live,
authenticated subscription.

## Run it

Against a running AccountShield instance (`docker compose up postgres app` from the repo root,
then in a second terminal):

```bash
cd sdk && mvn install -DskipTests
cd ../demo && mvn package
java -jar target/accountshield-demo.jar
```

**IDE note**: this is a standalone Maven project (no reactor) -- import `demo/pom.xml` as its own
Maven project in your IDE, the same way as `sdk/pom.xml` above.

Or through Docker Compose end to end from the repo root:

```bash
docker compose --profile demo up --build demo
```

Override the target instance with the `ACCOUNTSHIELD_BASE_URL` environment variable (default
`http://localhost:8080`; the Compose profile sets it to `http://app:8080` automatically).

## Authentication

Every endpoint this demo calls except the webhook demo sits behind AccountShield's JWT resource
server (ADR 0011). Set `ACCOUNTSHIELD_BEARER_TOKEN` to supply a token yourself; otherwise this demo
self-mints one via the server's `local`-profile-only `POST /dev/tokens` endpoint (dev/demo tooling
only -- a real consumer would obtain a token from its own identity provider integration). Both
`compose.yaml`'s `app` service and CI's smoke-test container run with `SPRING_PROFILES_ACTIVE=local`
specifically so this works out of the box.

A non-zero exit code means the demo detected an unexpected outcome or the server was unreachable --
this is exactly what CI's `docker` job checks on every PR (issue #55's "CI validates the end-to-end
flow").
