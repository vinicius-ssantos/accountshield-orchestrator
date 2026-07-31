import { BffError } from "../foundation";
import type { SessionRecord } from "./session-model";

export const SESSION_COOKIE_NAME_LOCAL = "as_session";
export const SESSION_COOKIE_NAME_SECURE = "__Host-as_session";

export interface SessionCookieConfig {
  cookieName: string;
  secureCookies: boolean;
  sessionSecret: string;
  absoluteTtlMs: number;
  inactivityTtlMs: number;
}

function boundedInteger(
  name: string,
  value: string | undefined,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  const parsed = value ? Number.parseInt(value, 10) : fallback;
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}.`);
  }
  return parsed;
}

function resolveSessionSecret(value: string | undefined, productionLike: boolean): string {
  const trimmed = value?.trim();
  if (productionLike) {
    if (!trimmed || trimmed.length < 32) {
      throw new Error(
        "ACCOUNTSHIELD_SESSION_SECRET must be set to a value of at least 32 characters outside local/test environments.",
      );
    }
    return trimmed;
  }
  return trimmed && trimmed.length >= 16 ? trimmed : "accountshield-local-only-session-secret";
}

/** __Host- requires HTTPS, so the secure-prefixed cookie name is only used where Secure is
 * guaranteed (the same productionLike boundary already used for HSTS). */
export function resolveSessionCookieConfig(
  source: Readonly<Record<string, string | undefined>>,
  productionLike: boolean,
): SessionCookieConfig {
  return {
    cookieName: productionLike ? SESSION_COOKIE_NAME_SECURE : SESSION_COOKIE_NAME_LOCAL,
    secureCookies: productionLike,
    sessionSecret: resolveSessionSecret(source.ACCOUNTSHIELD_SESSION_SECRET, productionLike),
    absoluteTtlMs: boundedInteger(
      "ACCOUNTSHIELD_SESSION_ABSOLUTE_TTL_MS",
      source.ACCOUNTSHIELD_SESSION_ABSOLUTE_TTL_MS,
      8 * 60 * 60 * 1000,
      5 * 60 * 1000,
      24 * 60 * 60 * 1000,
    ),
    inactivityTtlMs: boundedInteger(
      "ACCOUNTSHIELD_SESSION_INACTIVITY_TTL_MS",
      source.ACCOUNTSHIELD_SESSION_INACTIVITY_TTL_MS,
      20 * 60 * 1000,
      60 * 1000,
      4 * 60 * 60 * 1000,
    ),
  };
}

export function allowEnvTokenFallback(
  source: Readonly<Record<string, string | undefined>>,
  productionLike: boolean,
): boolean {
  if (productionLike) {
    return false;
  }
  return source.ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK?.trim().toLowerCase() === "true";
}

export interface CookieOptions {
  name: string;
  value: string;
  maxAgeSeconds: number;
  secure: boolean;
  httpOnly: boolean;
  sameSite: "Lax" | "Strict" | "None";
  path?: string;
}

export function serializeCookie(options: CookieOptions): string {
  const segments = [
    `${options.name}=${options.value}`,
    `Path=${options.path ?? "/"}`,
    `Max-Age=${Math.max(0, Math.floor(options.maxAgeSeconds))}`,
    `SameSite=${options.sameSite}`,
  ];
  if (options.secure) segments.push("Secure");
  if (options.httpOnly) segments.push("HttpOnly");
  return segments.join("; ");
}

export function clearCookie(options: Pick<CookieOptions, "name" | "secure" | "httpOnly" | "sameSite">): string {
  return serializeCookie({ ...options, value: "", maxAgeSeconds: 0 });
}

export function parseCookieHeader(header: string | null): Record<string, string> {
  const result: Record<string, string> = {};
  if (!header) return result;
  for (const part of header.split(";")) {
    const separatorIndex = part.indexOf("=");
    if (separatorIndex <= 0) continue;
    const name = part.slice(0, separatorIndex).trim();
    const value = part.slice(separatorIndex + 1).trim();
    if (!name) continue;
    try {
      result[name] = decodeURIComponent(value);
    } catch {
      result[name] = value;
    }
  }
  return result;
}

export interface LoginCredentials {
  username: string;
  password: string;
}

export function parseLoginCredentials(body: Record<string, unknown>): LoginCredentials {
  const username = body.username;
  const password = body.password;
  if (typeof username !== "string" || !username.trim() || username.length > 128) {
    throw new BffError("INVALID_REQUEST", 400, "The login request is invalid.");
  }
  if (typeof password !== "string" || !password || password.length > 256) {
    throw new BffError("INVALID_REQUEST", 400, "The login request is invalid.");
  }
  return { username: username.trim(), password };
}

export interface DecodedSessionClaims {
  subject: string;
  roles: readonly string[];
}

/**
 * Decodes (does NOT verify the signature of) a JWT payload segment. This is safe only because
 * it is called exclusively on a token this BFF just received directly from the trusted backend
 * origin over its own outbound fetch call -- never on a token supplied by a browser caller. All
 * real authorization stays backend-enforced; this is for local session bookkeeping and
 * read-only UI display ("signed in as X") only.
 */
export function decodeJwtPayloadUnsafe(token: string): DecodedSessionClaims {
  const segments = token.split(".");
  if (segments.length !== 3) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  let payload: unknown;
  try {
    payload = JSON.parse(Buffer.from(segments[1], "base64url").toString("utf8"));
  } catch (error) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
      cause: error,
    });
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const record = payload as Record<string, unknown>;
  const subject = record.sub;
  const roles = record.roles;
  if (
    typeof subject !== "string" ||
    !subject ||
    !Array.isArray(roles) ||
    roles.length === 0 ||
    !roles.every((role) => typeof role === "string")
  ) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  return { subject, roles: roles as string[] };
}

export interface SessionStatusBody {
  authenticated: boolean;
  state: "valid" | "expired" | "absent";
  subject?: string;
  roles?: readonly string[];
  expiresAt?: string;
}

/** Shapes the session-status response. Only ever includes subject/roles/expiresAt -- never
 * the session ID, CSRF secret, or backend token. */
export function buildSessionStatusBody(state: "expired" | "absent"): SessionStatusBody;
export function buildSessionStatusBody(state: "valid", record: SessionRecord): SessionStatusBody;
export function buildSessionStatusBody(
  state: "valid" | "expired" | "absent",
  record?: SessionRecord,
): SessionStatusBody {
  if (state !== "valid" || !record) {
    return { authenticated: false, state: state === "valid" ? "absent" : state };
  }
  return {
    authenticated: true,
    state: "valid",
    subject: record.subject,
    roles: record.roles,
    expiresAt: new Date(record.absoluteExpiresAt).toISOString(),
  };
}

export interface LoginSuccessBody {
  subject: string;
  roles: readonly string[];
  expiresAt: string;
}

/** Shapes the login-success response. Only ever includes subject/roles/expiresAt -- the
 * session cookie carries the session ID and the backend token never leaves the server. */
export function buildLoginSuccessBody(record: SessionRecord): LoginSuccessBody {
  return {
    subject: record.subject,
    roles: record.roles,
    expiresAt: new Date(record.absoluteExpiresAt).toISOString(),
  };
}
