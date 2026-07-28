# AccountShield SDK Demo

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

Or through Docker Compose end to end from the repo root:

```bash
docker compose --profile demo up --build demo
```

Override the target instance with the `ACCOUNTSHIELD_BASE_URL` environment variable (default
`http://localhost:8080`; the Compose profile sets it to `http://app:8080` automatically).

A non-zero exit code means the demo detected an unexpected outcome or the server was unreachable --
this is exactly what CI's `docker` job checks on every PR (issue #55's "CI validates the end-to-end
flow").
