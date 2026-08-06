import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import process from "node:process";

const APP_ENVIRONMENTS = new Set([
  "local",
  "test",
  "ci",
  "preview",
  "production",
]);
const DATA_SOURCES = new Set(["fixtures", "live"]);
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1"]);
const PUBLIC_SECRET_NAME =
  /^NEXT_PUBLIC_.*(?:SECRET|TOKEN|PASSWORD|CREDENTIAL|API_KEY|PRIVATE_KEY|HMAC)/i;

function fail(message) {
  console.error(`AccountShield frontend configuration error: ${message}`);
  process.exit(1);
}

function requiredEnum(name, value, allowed, fallback) {
  const resolved = value?.trim() || fallback;
  if (!allowed.has(resolved)) {
    fail(`${name} must be one of: ${Array.from(allowed).join(", ")}.`);
  }
  return resolved;
}

for (const [name, value] of Object.entries(process.env)) {
  if (!value) continue;

  if (name === "NEXT_PUBLIC_ACCOUNTSHIELD_API_URL") {
    fail(
      "NEXT_PUBLIC_ACCOUNTSHIELD_API_URL is forbidden; the backend origin is server-only.",
    );
  }

  if (PUBLIC_SECRET_NAME.test(name)) {
    fail(`${name} appears secret-bearing and cannot use the NEXT_PUBLIC_ prefix.`);
  }
}

const appEnvironment = requiredEnum(
  "NEXT_PUBLIC_APP_ENV",
  process.env.NEXT_PUBLIC_APP_ENV,
  APP_ENVIRONMENTS,
  "local",
);
const dataSource = requiredEnum(
  "ACCOUNTSHIELD_DATA_SOURCE",
  process.env.ACCOUNTSHIELD_DATA_SOURCE,
  DATA_SOURCES,
  "fixtures",
);
const productionLike =
  appEnvironment === "preview" || appEnvironment === "production";

if (productionLike && dataSource !== "live") {
  fail(`${appEnvironment} deployments must use ACCOUNTSHIELD_DATA_SOURCE=live.`);
}

const buildAppEnvironment = process.env.ACCOUNTSHIELD_BUILD_APP_ENV?.trim();
const buildDataSource = process.env.ACCOUNTSHIELD_BUILD_DATA_SOURCE?.trim();

if (buildAppEnvironment && buildAppEnvironment !== appEnvironment) {
  fail(
    `Runtime app environment ${appEnvironment} does not match image build environment ${buildAppEnvironment}.`,
  );
}

if (buildDataSource && buildDataSource !== dataSource) {
  fail(
    `Runtime data source ${dataSource} does not match image build data source ${buildDataSource}.`,
  );
}

const rawApiUrl = process.env.ACCOUNTSHIELD_API_URL?.trim();
if (dataSource === "live" && !rawApiUrl) {
  fail("Live data mode requires the server-only ACCOUNTSHIELD_API_URL.");
}

if (rawApiUrl) {
  let apiUrl;
  try {
    apiUrl = new URL(rawApiUrl);
  } catch {
    fail("ACCOUNTSHIELD_API_URL must be an absolute HTTP(S) origin.");
  }

  if (apiUrl.protocol !== "http:" && apiUrl.protocol !== "https:") {
    fail("ACCOUNTSHIELD_API_URL must use HTTP or HTTPS.");
  }
  if (apiUrl.username || apiUrl.password) {
    fail("ACCOUNTSHIELD_API_URL must not contain credentials.");
  }
  if (apiUrl.pathname !== "/" || apiUrl.search || apiUrl.hash) {
    fail("ACCOUNTSHIELD_API_URL must be an origin without path, query, or fragment.");
  }
  if (
    appEnvironment === "production" &&
    LOOPBACK_HOSTS.has(apiUrl.hostname.toLowerCase())
  ) {
    fail("Production ACCOUNTSHIELD_API_URL must not resolve to a loopback host.");
  }
}

const serverEntry = existsSync(".next/standalone/server.js")
  ? ".next/standalone/server.js"
  : "server.js";

if (!existsSync(serverEntry)) {
  fail(`Standalone server entry not found at ${serverEntry}. Run npm run build first.`);
}

const child = spawn(process.execPath, [serverEntry], {
  env: process.env,
  stdio: "inherit",
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}

child.on("error", (error) => {
  console.error("Failed to start the AccountShield frontend server.", error);
  process.exit(1);
});

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 1);
});
