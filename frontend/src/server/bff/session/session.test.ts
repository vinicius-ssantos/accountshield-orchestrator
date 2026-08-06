import { beforeEach, describe, expect, it, vi } from "vitest";

import { InMemoryBffTelemetrySink } from "../observability";
import { CSRF_HEADER_NAME, csrfCookieValue } from "./csrf";
import { buildSessionCookieValue } from "./session-crypto";
import { clearSessionStoreForTesting, createSession } from "./session-store";
import type { SessionRecord } from "./session-model";
import {
  AccountShieldSessionTokenService,
  handleSessionLoginRequest,
  handleSessionLogoutRequest,
  handleSessionRefreshRequest,
  handleSessionStatusRequest,
  type BackendTokenResponse,
  type SessionTokenService,
} from "./session";

const ENV = {
  NEXT_PUBLIC_APP_ENV: "test",
  ACCOUNTSHIELD_DATA_SOURCE: "live",
  ACCOUNTSHIELD_API_URL: "http://localhost:8080",
};
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";

function base64UrlJson(value: unknown): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function fakeBackendToken(subject = "operator-1", roles = ["SECURITY_OPERATOR"]): BackendTokenResponse {
  const token = `header.${base64UrlJson({ sub: subject, roles })}.signature`;
  return { token, expiresAt: new Date(Date.now() + 900_000).toISOString() };
}

function fakeService(overrides: Partial<SessionTokenService> = {}): SessionTokenService {
  return {
    issueToken: vi.fn(async () => fakeBackendToken()),
    refreshToken: vi.fn(async () => fakeBackendToken()),
    ...overrides,
  };
}

function loginRequest(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request(`${ORIGIN}/api/bff/session/login`, {
    method: "POST",
    headers: { "content-type": "application/json", "sec-fetch-site": "same-origin", ...headers },
    body: JSON.stringify(body),
  });
}

function storedRecordWithCookies(overrides: Partial<SessionRecord> = {}) {
  const now = Date.now();
  const record: SessionRecord = {
    sessionId: "session-id",
    subject: "operator-1",
    roles: ["SECURITY_OPERATOR"],
    backendToken: "stored-backend-token",
    backendTokenExpiresAt: now + 60_000,
    csrfSecret: "csrf-secret",
    createdAt: now,
    lastSeenAt: now,
    absoluteExpiresAt: now + 8 * 60 * 60 * 1000,
    ...overrides,
  };
  createSession(record);
  const cookieValue = buildSessionCookieValue(record.sessionId, SECRET);
  const csrfToken = csrfCookieValue(record.sessionId, record.csrfSecret, SECRET);
  return { record, cookieValue, csrfToken };
}

beforeEach(() => {
  clearSessionStoreForTesting();
});

describe("handleSessionLoginRequest", () => {
  it("succeeds, sets session and csrf cookies, and never returns the backend token or session cookie value in the body", async () => {
    const response = await handleSessionLoginRequest(
      loginRequest({ username: "operator-1", password: "correct" }),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);

    const setCookies = response.headers.getSetCookie();
    expect(setCookies.some((cookie) => cookie.startsWith("as_session="))).toBe(true);
    expect(setCookies.some((cookie) => cookie.startsWith("as_csrf="))).toBe(true);
    expect(setCookies.find((cookie) => cookie.startsWith("as_session="))).toContain("HttpOnly");
    expect(setCookies.find((cookie) => cookie.startsWith("as_csrf="))).not.toContain("HttpOnly");

    const body = await response.json();
    expect(body).toEqual({ subject: "operator-1", roles: ["SECURITY_OPERATOR"], expiresAt: expect.any(String) });
    const rawBody = JSON.stringify(body);
    expect(rawBody).not.toMatch(/eyJ/); // no JWT-shaped substring anywhere in the response body
  });

  it("passes wrong-credential failures through as a generic 401 without leaking backend detail", async () => {
    const { BffError } = await import("../foundation");
    const service = fakeService({
      issueToken: vi.fn(async () => {
        throw new BffError("UNAUTHORIZED", 401, "Invalid credentials.");
      }),
    });
    const response = await handleSessionLoginRequest(
      loginRequest({ username: "operator-1", password: "wrong" }),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(401);
    const problem = await response.json();
    expect(problem.code).toBe("UNAUTHORIZED");
  });

  it("rejects a malformed body with 400, not 500", async () => {
    const response = await handleSessionLoginRequest(loginRequest({ username: "" }), fakeService(), undefined, ENV);
    expect(response.status).toBe(400);
  });

  it("rejects a cross-site login request", async () => {
    const response = await handleSessionLoginRequest(
      loginRequest({ username: "operator-1", password: "correct" }, { "sec-fetch-site": "cross-site" }),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(403);
  });

  it("never logs the password or issued token", async () => {
    const sink = new InMemoryBffTelemetrySink();
    await handleSessionLoginRequest(
      loginRequest({ username: "operator-1", password: "super-secret-password" }),
      fakeService(),
      sink,
      ENV,
    );
    const serialized = JSON.stringify(sink.events);
    expect(serialized).not.toContain("super-secret-password");
    expect(serialized).not.toMatch(/eyJ/);
  });
});

describe("handleSessionLogoutRequest", () => {
  it("revokes the session and clears both cookies", async () => {
    const { cookieValue, csrfToken } = storedRecordWithCookies();
    const response = await handleSessionLogoutRequest(
      new Request(`${ORIGIN}/api/bff/session/logout`, {
        method: "POST",
        headers: {
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
          [CSRF_HEADER_NAME]: csrfToken,
        },
      }),
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const setCookies = response.headers.getSetCookie();
    expect(setCookies.find((cookie) => cookie.startsWith("as_session="))).toContain("Max-Age=0");

    const status = await handleSessionStatusRequest(
      new Request(`${ORIGIN}/api/bff/session/status`, {
        headers: { cookie: `as_session=${cookieValue}` },
      }),
      ENV,
    );
    expect((await status.json()).authenticated).toBe(false);
  });

  it("is idempotent when called twice", async () => {
    const { cookieValue, csrfToken } = storedRecordWithCookies();
    const logoutOnce = () =>
      handleSessionLogoutRequest(
        new Request(`${ORIGIN}/api/bff/session/logout`, {
          method: "POST",
          headers: {
            cookie: `as_session=${cookieValue}`,
            "sec-fetch-site": "same-origin",
            [CSRF_HEADER_NAME]: csrfToken,
          },
        }),
        undefined,
        ENV,
      );

    expect((await logoutOnce()).status).toBe(200);
    expect((await logoutOnce()).status).toBe(200);
  });

  it("rejects logout without a valid CSRF token", async () => {
    const { cookieValue } = storedRecordWithCookies();
    const response = await handleSessionLogoutRequest(
      new Request(`${ORIGIN}/api/bff/session/logout`, {
        method: "POST",
        headers: { cookie: `as_session=${cookieValue}`, "sec-fetch-site": "same-origin" },
      }),
      undefined,
      ENV,
    );
    expect(response.status).toBe(403);
  });
});

describe("handleSessionStatusRequest", () => {
  it("reports authenticated:false with no cookie", async () => {
    const response = await handleSessionStatusRequest(new Request(`${ORIGIN}/api/bff/session/status`), ENV);
    const body = await response.json();
    expect(body).toEqual({ authenticated: false, state: "absent" });
  });

  it("reports the subject and roles for a valid session", async () => {
    const { cookieValue } = storedRecordWithCookies();
    const response = await handleSessionStatusRequest(
      new Request(`${ORIGIN}/api/bff/session/status`, { headers: { cookie: `as_session=${cookieValue}` } }),
      ENV,
    );
    const body = await response.json();
    expect(body.authenticated).toBe(true);
    expect(body.subject).toBe("operator-1");
  });
});

describe("handleSessionRefreshRequest", () => {
  it("reissues the backend token and keeps the same session", async () => {
    const { cookieValue, csrfToken, record } = storedRecordWithCookies();
    const originalBackendToken = record.backendToken; // updateBackendToken mutates `record` in place
    const refreshed = fakeBackendToken();
    const service = fakeService({ refreshToken: vi.fn(async () => refreshed) });

    const response = await handleSessionRefreshRequest(
      new Request(`${ORIGIN}/api/bff/session/refresh`, {
        method: "POST",
        headers: {
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
          [CSRF_HEADER_NAME]: csrfToken,
        },
      }),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    expect(service.refreshToken).toHaveBeenCalledWith(originalBackendToken, expect.any(String));
  });

  it("revokes the session when the backend rejects the refresh", async () => {
    const { cookieValue, csrfToken } = storedRecordWithCookies();
    const service = fakeService({
      refreshToken: vi.fn(async () => {
        throw new Error("backend rejected refresh");
      }),
    });

    const response = await handleSessionRefreshRequest(
      new Request(`${ORIGIN}/api/bff/session/refresh`, {
        method: "POST",
        headers: {
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
          [CSRF_HEADER_NAME]: csrfToken,
        },
      }),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(401);

    const status = await handleSessionStatusRequest(
      new Request(`${ORIGIN}/api/bff/session/status`, { headers: { cookie: `as_session=${cookieValue}` } }),
      ENV,
    );
    expect((await status.json()).authenticated).toBe(false);
  });

  it("requires an existing session", async () => {
    const response = await handleSessionRefreshRequest(
      new Request(`${ORIGIN}/api/bff/session/refresh`, { method: "POST", headers: { "sec-fetch-site": "same-origin" } }),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(401);
  });
});

describe("AccountShieldSessionTokenService", () => {
  it("attaches the bearer token on refresh and posts credentials on login", async () => {
    const fetchImplementation = vi.fn<typeof fetch>(async () => {
      return new Response(JSON.stringify(fakeBackendToken()), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    });
    const service = new AccountShieldSessionTokenService({
      apiUrl: "http://localhost:8080",
      timeoutMs: 1000,
      fetchImplementation: fetchImplementation as unknown as typeof fetch,
    });

    await service.issueToken({ username: "operator-1", password: "x" }, "corr-1");
    await service.refreshToken("current-token", "corr-2");

    expect(fetchImplementation).toHaveBeenCalledTimes(2);
    const [loginUrl, loginInit] = fetchImplementation.mock.calls[0];
    expect(String(loginUrl)).toContain("/auth/session-tokens");
    expect(loginInit?.headers).toMatchObject({ "content-type": "application/json" });

    const [refreshUrl, refreshInit] = fetchImplementation.mock.calls[1];
    expect(String(refreshUrl)).toContain("/auth/session-tokens/refresh");
    expect(refreshInit?.headers).toMatchObject({ authorization: "Bearer current-token" });
  });

  it("maps a 401 from the backend to UNAUTHORIZED", async () => {
    const fetchImplementation = vi.fn(async () => new Response(null, { status: 401 }));
    const service = new AccountShieldSessionTokenService({
      apiUrl: "http://localhost:8080",
      timeoutMs: 1000,
      fetchImplementation: fetchImplementation as unknown as typeof fetch,
    });
    await expect(service.issueToken({ username: "x", password: "y" }, "corr")).rejects.toMatchObject({
      code: "UNAUTHORIZED",
    });
  });
});
