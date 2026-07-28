#!/usr/bin/env bash
# Seeds a running AccountShield instance with synthetic, representative decision data by running
# every named scenario through the Scenario CLI (issue #56) -- no hand-rolled seed SQL to keep in
# sync with the schema, and every seeded account reference is synthetic (.test domain), never real
# personal data (SECURITY.md).
#
# Usage: ./scripts/seed-demo-data.sh [base-url]
# Requires: docker compose up -d (app running with SPRING_PROFILES_ACTIVE=local, the default in
# compose.yaml, so this script can self-mint a token), and the sdk/cli modules built once:
#   cd sdk && mvn install -DskipTests && cd ../cli && mvn package

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_JAR="$REPO_ROOT/cli/target/accountshield-cli.jar"

if [ ! -f "$CLI_JAR" ]; then
  echo "error: $CLI_JAR not found. Build it first: cd sdk && mvn install -DskipTests && cd ../cli && mvn package" >&2
  exit 1
fi

echo "Minting a demo token from $BASE_URL/dev/tokens (requires SPRING_PROFILES_ACTIVE=local)..."
TOKEN=$(curl -sf -X POST "$BASE_URL/dev/tokens" \
  -H "Content-Type: application/json" \
  -d '{"subject":"seed-demo-data","roles":["PROTECTION_CLIENT"]}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "error: failed to mint a token. Is the app running with SPRING_PROFILES_ACTIVE=local?" >&2
  exit 1
fi

SCENARIOS=$(java -jar "$CLI_JAR" scenario list --json | grep -o '"name" : "[^"]*"' | cut -d'"' -f4)

for scenario in $SCENARIOS; do
  echo "Seeding scenario: $scenario"
  java -jar "$CLI_JAR" scenario run "$scenario" --base-url "$BASE_URL" --token "$TOKEN" || true
done

echo "Done. Seeded $(echo "$SCENARIOS" | wc -l) scenario(s) against $BASE_URL."
