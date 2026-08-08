\
# AccountShield Orchestrator — convenience commands.
# Install just: https://github.com/casey/just (`winget install Casey.Just`).
# Run `just` with no arguments to list all recipes.

set shell := ["bash", "-cu"]

default:
    @just --list

# --- Run ---------------------------------------------------------------

# Start Postgres (via Docker Compose) and the Spring Boot backend in the foreground.
backend: postgres
    ./mvnw spring-boot:run

# Start the Next.js frontend dev server in the foreground (fixture data source by default).
frontend:
    cd frontend && npm run dev

# Start Postgres + backend in the background and the frontend in the foreground.
# Ctrl+C stops the frontend and tears down the backend.
dev: postgres
    #!/usr/bin/env bash
    set -euo pipefail
    ./mvnw spring-boot:run &
    backend_pid=$!
    trap 'kill "$backend_pid" 2>/dev/null || true' EXIT
    cd frontend && npm run dev

# Start only the Postgres service from compose.yaml.
postgres:
    docker compose up -d postgres

# Start the full stack (Postgres, backend, frontend) as built Docker images.
up:
    docker compose up -d

# Stop and remove all compose containers.
down:
    docker compose down

# Tail logs from every compose service.
logs:
    docker compose logs -f

# --- Build & verify ------------------------------------------------------

# Run the full backend verification suite (unit + integration + architecture + coverage).
verify:
    ./mvnw --batch-mode --no-transfer-progress verify

# Run the backend suite the way ci.yml's default PR gate does (nightly-only lanes excluded).
# Requires `cd sdk && mvn install` first (issue #148): -Psdk-contract-verification activates
# SdkContractVerificationTest, opt-in on a clean clone but always run in CI.
verify-fast:
    ./mvnw --batch-mode --no-transfer-progress verify -Psdk-contract-verification -DexcludedGroups=resilience,benchmark,disaster-recovery

# Install frontend dependencies.
frontend-install:
    cd frontend && npm install

# Run the full frontend verification pipeline (openapi, architecture, lint, typecheck, unit, build).
frontend-verify: frontend-install
    cd frontend && npm run openapi:check
    cd frontend && npm run architecture:test
    cd frontend && npm run architecture:check
    cd frontend && npm run lint
    cd frontend && npm run typecheck
    cd frontend && npm run test:unit
    cd frontend && npm run build

# Run frontend Playwright end-to-end tests (requires a prior `npm run build`).
frontend-e2e:
    cd frontend && npm run test:e2e

# Run frontend accessibility-tagged Playwright tests only.
frontend-a11y:
    cd frontend && npm run test:a11y

# --- Housekeeping --------------------------------------------------------

# Remove backend and frontend build output.
clean:
    ./mvnw clean
    rm -rf frontend/.next frontend/coverage frontend/playwright-report frontend/test-results
