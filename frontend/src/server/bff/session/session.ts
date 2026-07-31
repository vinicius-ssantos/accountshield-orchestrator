// Orchestrates the operator login/logout/status/refresh BFF endpoints. Deliberately does not
// import the literal "server-only" npm package: the real architecture boundary (ARCH001) is
// enforced by this file's path under src/server/ regardless of that import (see
// architecture-analyzer.mjs), and skipping it here lets this file's handlers be exercised
// directly by vitest (whose global jsdom environment defines `window`, which the "server-only"
// package treats as a client bundle and throws on import) -- the same testability trade-off
// session-crypto.ts/session-store.ts/session-core.ts/csrf.ts already make.
import { readFrontendEnvironment } from "@/config/environment";

import { BffError, assertRequestPolicy, readJsonObject, resolveCorrelationId, toProblemDetails } from "../foundation";
import { startBffTelemetry, type BffTelemetrySink } from "../observability";
import { CSRF_COOKIE_NAME, csrfCookieValue, isCsrfTokenValid, isTrustedOrigin } from "./csrf";
import {
  buildLoginSuccessBody,
  buildSessionStatusBody,
  clearCookie,
  decodeJwtPayloadUnsafe,
  parseCookieHeader,
  parseLoginCredentials,
  resolveSessionCookieConfig,
  serializeCookie,
  type LoginCredentials,
  type SessionCookieConfig,
} from "./session-core";
import { buildSessionCookieValue, generateCsrfSecret, generateSessionId, verifySessionCookieValue } from "./session-crypto";
import type { SessionRecord } from "./session-model";
import {
  createSession,
  getSession,
  revokeSession,
  touchSession,
  updateBackendToken,
} from "./session-store";

const CACHE_CONTROL = "private, no-store, max-age=0, must-revalidate";
const LOGIN_MAX_BYTES = 1024;

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

export interface BackendTokenResponse {
  token: string;
  expiresAt: string;
}

export interface SessionTokenService {
  issueToken(credentials: LoginCredentials, correlationId: string): Promise<BackendTokenResponse>;
  refreshToken(currentToken: string, correlationId: string): Promise<BackendTokenResponse>;
}

interface SessionRuntimeConfig extends SessionCookieConfig {
  apiUrl: string;
  timeoutMs: number;
}

function resolveConfig(source: Readonly<Record<string, string | undefined>> = process.env): SessionRuntimeConfig {
  const environment = readFrontendEnvironment(source, "runtime");
  if (!environment.apiUrl) {
    throw new BffError("UPSTREAM_UNAVAILABLE", 503, "Operator login is not configured.", true);
  }
  return {
    apiUrl: environment.apiUrl,
    timeoutMs: boundedInteger("ACCOUNTSHIELD_BFF_TIMEOUT_MS", source.ACCOUNTSHIELD_BFF_TIMEOUT_MS, 4_000, 100, 15_000),
    ...resolveSessionCookieConfig(source, environment.productionLike),
  };
}

async function parseBackendTokenResponse(response: Response): Promise<BackendTokenResponse> {
  const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
  if (contentType !== "application/json") {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch (error) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
      cause: error,
    });
  }
  if (
    !body ||
    typeof body !== "object" ||
    typeof (body as Record<string, unknown>).token !== "string" ||
    typeof (body as Record<string, unknown>).expiresAt !== "string"
  ) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  return body as BackendTokenResponse;
}

export class AccountShieldSessionTokenService implements SessionTokenService {
  constructor(
    private readonly configuration: {
      apiUrl: string;
      timeoutMs: number;
      fetchImplementation?: typeof fetch;
    },
  ) {}

  async issueToken(credentials: LoginCredentials, correlationId: string): Promise<BackendTokenResponse> {
    return this.execute("/auth/session-tokens", correlationId, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(credentials),
    });
  }

  async refreshToken(currentToken: string, correlationId: string): Promise<BackendTokenResponse> {
    return this.execute("/auth/session-tokens/refresh", correlationId, {
      method: "POST",
      headers: { authorization: `Bearer ${currentToken}` },
    });
  }

  private async execute(
    path: string,
    correlationId: string,
    init: { method: string; headers: Record<string, string>; body?: string },
  ): Promise<BackendTokenResponse> {
    const fetchImplementation = this.configuration.fetchImplementation ?? fetch;
    const timeoutSignal = AbortSignal.timeout(this.configuration.timeoutMs);

    let response: Response;
    try {
      response = await fetchImplementation(new URL(path, this.configuration.apiUrl), {
        method: init.method,
        headers: { accept: "application/json", "x-correlation-id": correlationId, ...init.headers },
        body: init.body,
        cache: "no-store",
        signal: timeoutSignal,
      });
    } catch (error) {
      if (timeoutSignal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The session service timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The session service is unavailable.", true, {
        cause: error,
      });
    }

    if (response.status === 401) {
      throw new BffError("UNAUTHORIZED", 401, "Invalid credentials.");
    }
    if (response.status === 400) {
      throw new BffError("INVALID_REQUEST", 400, "The session request is invalid.");
    }
    if (response.status !== 200) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The session service is unavailable.", true);
    }

    return parseBackendTokenResponse(response);
  }
}

function createSessionTokenService(config: SessionRuntimeConfig): SessionTokenService {
  return new AccountShieldSessionTokenService({ apiUrl: config.apiUrl, timeoutMs: config.timeoutMs });
}

function problemResponse(problem: ReturnType<typeof toProblemDetails>): Response {
  const headers = new Headers({
    "cache-control": CACHE_CONTROL,
    "content-type": "application/problem+json",
    "x-correlation-id": problem.correlationId,
  });
  if (problem.code === "METHOD_NOT_ALLOWED") headers.set("allow", "POST");
  return new Response(JSON.stringify(problem), { status: problem.status, headers });
}

function sessionCookieHeaders(config: SessionRuntimeConfig, sessionId: string, csrfSecret: string): Headers {
  const headers = new Headers();
  headers.append(
    "set-cookie",
    serializeCookie({
      name: config.cookieName,
      value: buildSessionCookieValue(sessionId, config.sessionSecret),
      maxAgeSeconds: Math.floor(config.absoluteTtlMs / 1000),
      secure: config.secureCookies,
      httpOnly: true,
      sameSite: "Lax",
    }),
  );
  headers.append(
    "set-cookie",
    serializeCookie({
      name: CSRF_COOKIE_NAME,
      value: csrfCookieValue(sessionId, csrfSecret, config.sessionSecret),
      maxAgeSeconds: Math.floor(config.absoluteTtlMs / 1000),
      secure: config.secureCookies,
      httpOnly: false,
      sameSite: "Lax",
    }),
  );
  return headers;
}

function clearSessionCookieHeaders(config: SessionCookieConfig): Headers {
  const headers = new Headers();
  headers.append(
    "set-cookie",
    clearCookie({ name: config.cookieName, secure: config.secureCookies, httpOnly: true, sameSite: "Lax" }),
  );
  headers.append(
    "set-cookie",
    clearCookie({ name: CSRF_COOKIE_NAME, secure: config.secureCookies, httpOnly: false, sameSite: "Lax" }),
  );
  return headers;
}

function readSessionId(request: Request, config: SessionCookieConfig): string | undefined {
  const cookies = parseCookieHeader(request.headers.get("cookie"));
  const cookieValue = cookies[config.cookieName];
  return cookieValue ? verifySessionCookieValue(cookieValue, config.sessionSecret) : undefined;
}

export async function handleSessionLoginRequest(
  request: Request,
  service?: SessionTokenService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({ useCase: "session_login", correlationId, sink: telemetrySink });

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["POST"],
      allowedContentTypes: ["application/json"],
      maxBodyBytes: LOGIN_MAX_BYTES,
    });
    if (!isTrustedOrigin(request, new URL(request.url).origin)) {
      throw new BffError("FORBIDDEN", 403, "The request origin is not trusted.");
    }

    const body = await readJsonObject(request, LOGIN_MAX_BYTES);
    const credentials = parseLoginCredentials(body);
    const config = resolveConfig(source);
    const backendToken = await (service ?? createSessionTokenService(config)).issueToken(credentials, correlationId);
    const claims = decodeJwtPayloadUnsafe(backendToken.token);

    const now = Date.now();
    const sessionId = generateSessionId();
    const csrfSecret = generateCsrfSecret();
    const record: SessionRecord = {
      sessionId,
      subject: claims.subject,
      roles: claims.roles,
      backendToken: backendToken.token,
      backendTokenExpiresAt: Date.parse(backendToken.expiresAt),
      csrfSecret,
      createdAt: now,
      lastSeenAt: now,
      absoluteExpiresAt: now + config.absoluteTtlMs,
    };
    createSession(record);

    const headers = sessionCookieHeaders(config, sessionId, csrfSecret);
    headers.set("cache-control", CACHE_CONTROL);
    headers.set("x-correlation-id", correlationId);

    telemetry.succeed(200);
    return new Response(JSON.stringify(buildLoginSuccessBody(record)), { status: 200, headers });
  } catch (error) {
    const problem = toProblemDetails(error, correlationId);
    if (request.signal.aborted) telemetry.cancel();
    else telemetry.fail(error, problem.status);
    return problemResponse(problem);
  }
}

export async function handleSessionLogoutRequest(
  request: Request,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({ useCase: "session_logout", correlationId, sink: telemetrySink });

  try {
    assertRequestPolicy(request, { allowedMethods: ["POST"] });
    const environment = readFrontendEnvironment(source, "runtime");
    const config = resolveSessionCookieConfig(source, environment.productionLike);
    const sessionId = readSessionId(request, config);

    if (sessionId) {
      const lookup = getSession(sessionId, Date.now(), config);
      if (lookup.state === "valid") {
        if (!isTrustedOrigin(request, new URL(request.url).origin)) {
          throw new BffError("FORBIDDEN", 403, "The request origin is not trusted.");
        }
        if (!isCsrfTokenValid(request, sessionId, lookup.record.csrfSecret, config.sessionSecret)) {
          throw new BffError("FORBIDDEN", 403, "CSRF validation failed.");
        }
        revokeSession(sessionId);
      }
    }

    const headers = clearSessionCookieHeaders(config);
    headers.set("cache-control", CACHE_CONTROL);
    headers.set("x-correlation-id", correlationId);

    telemetry.succeed(200);
    return new Response(JSON.stringify({ loggedOut: true }), { status: 200, headers });
  } catch (error) {
    const problem = toProblemDetails(error, correlationId);
    if (request.signal.aborted) telemetry.cancel();
    else telemetry.fail(error, problem.status);
    return problemResponse(problem);
  }
}

export async function handleSessionStatusRequest(
  request: Request,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));

  try {
    assertRequestPolicy(request, { allowedMethods: ["GET"] });
    const environment = readFrontendEnvironment(source, "runtime");
    const config = resolveSessionCookieConfig(source, environment.productionLike);
    const sessionId = readSessionId(request, config);
    const lookup = sessionId
      ? getSession(sessionId, Date.now(), config)
      : ({ state: "absent", record: undefined } as const);

    const headers = new Headers({ "cache-control": CACHE_CONTROL, "x-correlation-id": correlationId });
    if (lookup.state !== "valid") {
      return new Response(JSON.stringify(buildSessionStatusBody(lookup.state)), { status: 200, headers });
    }

    touchSession(sessionId as string, Date.now());
    return new Response(JSON.stringify(buildSessionStatusBody("valid", lookup.record)), { status: 200, headers });
  } catch (error) {
    return problemResponse(toProblemDetails(error, correlationId));
  }
}

export async function handleSessionRefreshRequest(
  request: Request,
  service?: SessionTokenService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({ useCase: "session_refresh", correlationId, sink: telemetrySink });

  try {
    assertRequestPolicy(request, { allowedMethods: ["POST"] });
    const config = resolveConfig(source);
    const sessionId = readSessionId(request, config);
    if (!sessionId) {
      throw new BffError("UNAUTHORIZED", 401, "A valid session is required.");
    }

    const lookup = getSession(sessionId, Date.now(), config);
    if (lookup.state !== "valid") {
      throw new BffError("UNAUTHORIZED", 401, "A valid session is required.");
    }
    if (
      !isTrustedOrigin(request, new URL(request.url).origin) ||
      !isCsrfTokenValid(request, sessionId, lookup.record.csrfSecret, config.sessionSecret)
    ) {
      throw new BffError("FORBIDDEN", 403, "CSRF validation failed.");
    }

    let backendToken: BackendTokenResponse;
    try {
      backendToken = await (service ?? createSessionTokenService(config)).refreshToken(
        lookup.record.backendToken,
        correlationId,
      );
    } catch (error) {
      revokeSession(sessionId);
      throw new BffError("UNAUTHORIZED", 401, "The session could not be refreshed.", false, { cause: error });
    }
    updateBackendToken(sessionId, backendToken.token, Date.parse(backendToken.expiresAt));
    touchSession(sessionId, Date.now());

    const headers = new Headers({ "cache-control": CACHE_CONTROL, "x-correlation-id": correlationId });
    telemetry.succeed(200);
    return new Response(
      JSON.stringify({ refreshed: true, expiresAt: new Date(lookup.record.absoluteExpiresAt).toISOString() }),
      { status: 200, headers },
    );
  } catch (error) {
    const problem = toProblemDetails(error, correlationId);
    if (request.signal.aborted) telemetry.cancel();
    else telemetry.fail(error, problem.status);
    return problemResponse(problem);
  }
}
