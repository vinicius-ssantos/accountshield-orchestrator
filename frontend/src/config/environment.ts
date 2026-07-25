export type AppEnvironment =
  | "local"
  | "test"
  | "ci"
  | "preview"
  | "production";

export type AccountShieldDataSource = "fixtures" | "live";
export type ValidationPhase = "build" | "runtime";

type EnvironmentSource = Readonly<Record<string, string | undefined>>;

export interface FrontendEnvironment {
  appEnvironment: AppEnvironment;
  dataSource: AccountShieldDataSource;
  apiUrl?: string;
  productionLike: boolean;
}

const APP_ENVIRONMENTS = new Set<AppEnvironment>([
  "local",
  "test",
  "ci",
  "preview",
  "production",
]);
const DATA_SOURCES = new Set<AccountShieldDataSource>(["fixtures", "live"]);
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1"]);
const PUBLIC_SECRET_NAME =
  /^NEXT_PUBLIC_.*(?:SECRET|TOKEN|PASSWORD|CREDENTIAL|API_KEY|PRIVATE_KEY|HMAC)/i;

function requiredEnum<T extends string>(
  name: string,
  value: string | undefined,
  allowed: ReadonlySet<T>,
  fallback: T,
): T {
  const resolved = value?.trim() || fallback;

  if (!allowed.has(resolved as T)) {
    throw new Error(
      `${name} must be one of: ${Array.from(allowed).join(", ")}.`,
    );
  }

  return resolved as T;
}

function validatePublicVariables(source: EnvironmentSource): void {
  for (const [name, value] of Object.entries(source)) {
    if (!value) {
      continue;
    }

    if (name === "NEXT_PUBLIC_ACCOUNTSHIELD_API_URL") {
      throw new Error(
        "NEXT_PUBLIC_ACCOUNTSHIELD_API_URL is forbidden; the backend origin is server-only.",
      );
    }

    if (PUBLIC_SECRET_NAME.test(name)) {
      throw new Error(
        `${name} appears secret-bearing and cannot use the NEXT_PUBLIC_ prefix.`,
      );
    }
  }
}

function normalizeApiUrl(
  rawValue: string | undefined,
  appEnvironment: AppEnvironment,
): string | undefined {
  const value = rawValue?.trim();
  if (!value) {
    return undefined;
  }

  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("ACCOUNTSHIELD_API_URL must be an absolute HTTP(S) origin.");
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("ACCOUNTSHIELD_API_URL must use HTTP or HTTPS.");
  }

  if (url.username || url.password) {
    throw new Error("ACCOUNTSHIELD_API_URL must not contain credentials.");
  }

  if (url.pathname !== "/" || url.search || url.hash) {
    throw new Error(
      "ACCOUNTSHIELD_API_URL must be an origin without path, query, or fragment.",
    );
  }

  if (
    appEnvironment === "production" &&
    LOOPBACK_HOSTS.has(url.hostname.toLowerCase())
  ) {
    throw new Error(
      "Production ACCOUNTSHIELD_API_URL must not resolve to a loopback host.",
    );
  }

  return url.origin;
}

function validateBuildRuntimeParity(
  source: EnvironmentSource,
  appEnvironment: AppEnvironment,
  dataSource: AccountShieldDataSource,
): void {
  const buildAppEnvironment = source.ACCOUNTSHIELD_BUILD_APP_ENV?.trim();
  const buildDataSource = source.ACCOUNTSHIELD_BUILD_DATA_SOURCE?.trim();

  if (buildAppEnvironment && buildAppEnvironment !== appEnvironment) {
    throw new Error(
      `Runtime app environment ${appEnvironment} does not match image build environment ${buildAppEnvironment}.`,
    );
  }

  if (buildDataSource && buildDataSource !== dataSource) {
    throw new Error(
      `Runtime data source ${dataSource} does not match image build data source ${buildDataSource}.`,
    );
  }
}

export function readFrontendEnvironment(
  source: EnvironmentSource = process.env,
  phase: ValidationPhase = "runtime",
): FrontendEnvironment {
  validatePublicVariables(source);

  const appEnvironment = requiredEnum(
    "NEXT_PUBLIC_APP_ENV",
    source.NEXT_PUBLIC_APP_ENV,
    APP_ENVIRONMENTS,
    "local",
  );
  const dataSource = requiredEnum(
    "ACCOUNTSHIELD_DATA_SOURCE",
    source.ACCOUNTSHIELD_DATA_SOURCE,
    DATA_SOURCES,
    "fixtures",
  );
  const productionLike =
    appEnvironment === "preview" || appEnvironment === "production";
  const apiUrl = normalizeApiUrl(
    source.ACCOUNTSHIELD_API_URL,
    appEnvironment,
  );

  if (productionLike && dataSource !== "live") {
    throw new Error(
      `${appEnvironment} deployments must use ACCOUNTSHIELD_DATA_SOURCE=live.`,
    );
  }

  if (phase === "runtime") {
    validateBuildRuntimeParity(source, appEnvironment, dataSource);

    if (dataSource === "live" && !apiUrl) {
      throw new Error(
        "Live data mode requires the server-only ACCOUNTSHIELD_API_URL.",
      );
    }
  }

  return {
    appEnvironment,
    dataSource,
    apiUrl,
    productionLike,
  };
}
