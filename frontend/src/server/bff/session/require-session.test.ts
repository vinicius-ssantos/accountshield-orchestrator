import { beforeEach, describe, expect, it } from "vitest";

import { BffError } from "../foundation";
import { CSRF_HEADER_NAME, csrfCookieValue } from "./csrf";
import { buildSessionCookieValue } from "./session-crypto";
import type { SessionRecord } from "./session-model";
import { clearSessionStoreForTesting, createSession, revokeSession } from "./session-store";
import { canFallBackToEnvToken, requireOperatorSession, resolveOperatorToken } from "./require-session";

const ENV = { NEXT_PUBLIC_APP_ENV: "test" };
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";

function storedRecordWithCookies(overrides: Partial<SessionRecord> = {}) {
  const now = Date.now();
  const record: SessionRecord = {
    sessionId: "session-id",
    subject: "operator-1",
    roles: ["SECURITY_OPERATOR"],
    backendToken: "backend-token",
    backendTokenExpiresAt: now + 60_000,
    csrfSecret: "csrf-secret",
    createdAt: now,
    lastSeenAt: now,
    absoluteExpiresAt: now + 8 * 60 * 60 * 1000,
    ...overrides,
  };
  createSession(record);
  return {
    record,
    cookieValue: buildSessionCookieValue(record.sessionId, SECRET),
    csrfToken: csrfCookieValue(record.sessionId, record.csrfSecret, SECRET),
  };
}

function expectBffError(callback: () => void): BffError {
  try {
    callback();
  } catch (error) {
    expect(error).toBeInstanceOf(BffError);
    return error as BffError;
  }
  throw new Error("expected callback to throw");
}

beforeEach(() => {
  clearSessionStoreForTesting();
});

describe("requireOperatorSession", () => {
  it("passes through a GET request with a valid session cookie and no CSRF header required", () => {
    const { record, cookieValue } = storedRecordWithCookies();
    const authorized = requireOperatorSession(
      new Request(`${ORIGIN}/api/bff/decision-search`, { headers: { cookie: `as_session=${cookieValue}` } }),
      ENV,
    );
    expect(authorized).toEqual({ subject: record.subject, roles: record.roles, backendToken: record.backendToken });
  });

  it("rejects a missing session cookie", () => {
    expect(
      expectBffError(() => requireOperatorSession(new Request(`${ORIGIN}/api/bff/decision-search`), ENV)),
    ).toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("rejects an expired session", () => {
    const { cookieValue } = storedRecordWithCookies({ absoluteExpiresAt: Date.now() - 1 });
    expect(
      expectBffError(() =>
        requireOperatorSession(
          new Request(`${ORIGIN}/api/bff/decision-search`, { headers: { cookie: `as_session=${cookieValue}` } }),
          ENV,
        ),
      ),
    ).toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("rejects a revoked (deleted) session identically to a missing one", () => {
    const { cookieValue, record } = storedRecordWithCookies();
    revokeSession(record.sessionId);
    expect(
      expectBffError(() =>
        requireOperatorSession(
          new Request(`${ORIGIN}/api/bff/decision-search`, { headers: { cookie: `as_session=${cookieValue}` } }),
          ENV,
        ),
      ),
    ).toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("requires CSRF validation for a mutating method", () => {
    const { cookieValue } = storedRecordWithCookies();
    expect(
      expectBffError(() =>
        requireOperatorSession(
          new Request(`${ORIGIN}/api/bff/decision-search`, {
            method: "POST",
            headers: { cookie: `as_session=${cookieValue}`, "sec-fetch-site": "same-origin" },
          }),
          ENV,
        ),
      ),
    ).toMatchObject({ code: "FORBIDDEN", status: 403 });
  });

  it("accepts a mutating request with a valid CSRF token", () => {
    const { cookieValue, csrfToken } = storedRecordWithCookies();
    const authorized = requireOperatorSession(
      new Request(`${ORIGIN}/api/bff/decision-search`, {
        method: "POST",
        headers: {
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
          [CSRF_HEADER_NAME]: csrfToken,
        },
      }),
      ENV,
    );
    expect(authorized.subject).toBe("operator-1");
  });

  it("rejects a mutating request from an untrusted origin even with a valid CSRF token", () => {
    const { cookieValue, csrfToken } = storedRecordWithCookies();
    expect(
      expectBffError(() =>
        requireOperatorSession(
          new Request(`${ORIGIN}/api/bff/decision-search`, {
            method: "POST",
            headers: {
              cookie: `as_session=${cookieValue}`,
              "sec-fetch-site": "cross-site",
              [CSRF_HEADER_NAME]: csrfToken,
            },
          }),
          ENV,
        ),
      ),
    ).toMatchObject({ code: "FORBIDDEN", status: 403 });
  });
});

describe("canFallBackToEnvToken", () => {
  it("is false by default", () => {
    expect(canFallBackToEnvToken(ENV)).toBe(false);
  });

  it("is true only when explicitly enabled outside productionLike", () => {
    expect(canFallBackToEnvToken({ ...ENV, ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true" })).toBe(true);
  });

  it("is forced false when productionLike even if the flag is set", () => {
    expect(
      canFallBackToEnvToken({
        NEXT_PUBLIC_APP_ENV: "production",
        ACCOUNTSHIELD_DATA_SOURCE: "live",
        ACCOUNTSHIELD_API_URL: "https://api.example.com",
        ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true",
      }),
    ).toBe(false);
  });
});

describe("resolveOperatorToken", () => {
  it("uses the authenticated session's stored backend token when a valid session exists", () => {
    const { record, cookieValue } = storedRecordWithCookies({ backendToken: "session-backend-token" });
    const token = resolveOperatorToken(
      new Request(`${ORIGIN}/api/bff/decision-search`, { headers: { cookie: `as_session=${cookieValue}` } }),
      ENV,
    );
    expect(token).toBe(record.backendToken);
  });

  it("prefers the session token over the env fallback even when the fallback is allowed", () => {
    const { cookieValue } = storedRecordWithCookies({ backendToken: "session-backend-token" });
    const token = resolveOperatorToken(
      new Request(`${ORIGIN}/api/bff/decision-search`, { headers: { cookie: `as_session=${cookieValue}` } }),
      { ...ENV, ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true", ACCOUNTSHIELD_OPERATOR_TOKEN: "env-token" },
    );
    expect(token).toBe("session-backend-token");
  });

  it("falls back to ACCOUNTSHIELD_OPERATOR_TOKEN when no session exists and the fallback is explicitly allowed", () => {
    const token = resolveOperatorToken(new Request(`${ORIGIN}/api/bff/decision-search`), {
      ...ENV,
      ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true",
      ACCOUNTSHIELD_OPERATOR_TOKEN: "env-token",
    });
    expect(token).toBe("env-token");
  });

  it("rejects with the original UNAUTHORIZED error when no session exists and the fallback is not allowed", () => {
    expect(
      expectBffError(() => resolveOperatorToken(new Request(`${ORIGIN}/api/bff/decision-search`), ENV)),
    ).toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("rejects when no session exists, the fallback is allowed, but no env token is configured", () => {
    expect(
      expectBffError(() =>
        resolveOperatorToken(new Request(`${ORIGIN}/api/bff/decision-search`), {
          ...ENV,
          ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true",
        }),
      ),
    ).toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("never falls back to the env token on a CSRF/origin failure -- only on a missing session", () => {
    const { cookieValue, csrfToken } = storedRecordWithCookies();
    expect(
      expectBffError(() =>
        resolveOperatorToken(
          new Request(`${ORIGIN}/api/bff/decision-search`, {
            method: "POST",
            headers: {
              cookie: `as_session=${cookieValue}`,
              "sec-fetch-site": "cross-site",
              [CSRF_HEADER_NAME]: csrfToken,
            },
          }),
          { ...ENV, ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true", ACCOUNTSHIELD_OPERATOR_TOKEN: "env-token" },
        ),
      ),
    ).toMatchObject({ code: "FORBIDDEN", status: 403 });
  });
});
