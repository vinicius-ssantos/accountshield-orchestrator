import { describe, expect, it } from "vitest";

import { BffError } from "../foundation";
import {
  allowEnvTokenFallback,
  buildLoginSuccessBody,
  buildSessionStatusBody,
  clearCookie,
  decodeJwtPayloadUnsafe,
  parseCookieHeader,
  parseLoginCredentials,
  resolveSessionCookieConfig,
  serializeCookie,
} from "./session-core";
import type { SessionRecord } from "./session-model";

function expectBffError(callback: () => void): BffError {
  try {
    callback();
  } catch (error) {
    expect(error).toBeInstanceOf(BffError);
    return error as BffError;
  }
  throw new Error("expected callback to throw");
}

function base64UrlJson(value: unknown): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

const RECORD: SessionRecord = {
  sessionId: "session-id",
  subject: "operator-1",
  roles: ["SECURITY_OPERATOR"],
  backendToken: "backend-token-should-never-leak",
  backendTokenExpiresAt: Date.now() + 900_000,
  csrfSecret: "csrf-secret-should-never-leak",
  createdAt: Date.now(),
  lastSeenAt: Date.now(),
  absoluteExpiresAt: Date.now() + 28_800_000,
};

describe("resolveSessionCookieConfig", () => {
  it("uses the __Host- prefix and Secure only when productionLike", () => {
    const config = resolveSessionCookieConfig({ ACCOUNTSHIELD_SESSION_SECRET: "x".repeat(32) }, true);
    expect(config.cookieName).toBe("__Host-as_session");
    expect(config.secureCookies).toBe(true);
  });

  it("uses a plain cookie name and no Secure flag outside productionLike", () => {
    const config = resolveSessionCookieConfig({}, false);
    expect(config.cookieName).toBe("as_session");
    expect(config.secureCookies).toBe(false);
  });

  it("requires a session secret of at least 32 characters when productionLike", () => {
    expect(() => resolveSessionCookieConfig({ ACCOUNTSHIELD_SESSION_SECRET: "too-short" }, true)).toThrow();
    expect(() => resolveSessionCookieConfig({}, true)).toThrow();
  });

  it("falls back to a documented local-only secret outside productionLike", () => {
    const config = resolveSessionCookieConfig({}, false);
    expect(config.sessionSecret).toBe("accountshield-local-only-session-secret");
  });

  it("rejects an out-of-bounds TTL override", () => {
    expect(() =>
      resolveSessionCookieConfig(
        { ACCOUNTSHIELD_SESSION_ABSOLUTE_TTL_MS: "999999999999" },
        false,
      ),
    ).toThrow();
  });
});

describe("allowEnvTokenFallback", () => {
  it("is always false when productionLike, regardless of the flag", () => {
    expect(allowEnvTokenFallback({ ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true" }, true)).toBe(false);
  });

  it("honors the flag outside productionLike", () => {
    expect(allowEnvTokenFallback({ ACCOUNTSHIELD_ALLOW_ENV_TOKEN_FALLBACK: "true" }, false)).toBe(true);
    expect(allowEnvTokenFallback({}, false)).toBe(false);
  });
});

describe("cookie serialization", () => {
  it("builds an HttpOnly, SameSite=Lax cookie header", () => {
    const header = serializeCookie({
      name: "as_session",
      value: "abc",
      maxAgeSeconds: 3600,
      secure: true,
      httpOnly: true,
      sameSite: "Lax",
    });
    expect(header).toContain("as_session=abc");
    expect(header).toContain("HttpOnly");
    expect(header).toContain("Secure");
    expect(header).toContain("SameSite=Lax");
    expect(header).toContain("Max-Age=3600");
  });

  it("clears a cookie with Max-Age=0 and an empty value", () => {
    const header = clearCookie({ name: "as_session", secure: true, httpOnly: true, sameSite: "Lax" });
    expect(header).toContain("as_session=;");
    expect(header).toContain("Max-Age=0");
  });

  it("parses a Cookie header into a name/value map", () => {
    expect(parseCookieHeader("as_session=abc; as_csrf=def")).toEqual({
      as_session: "abc",
      as_csrf: "def",
    });
  });

  it("parses malformed cookie headers without throwing", () => {
    expect(parseCookieHeader(null)).toEqual({});
    expect(parseCookieHeader("")).toEqual({});
    expect(parseCookieHeader("garbage;;;=")).toEqual({});
  });
});

describe("parseLoginCredentials", () => {
  it("accepts a well-formed body", () => {
    expect(parseLoginCredentials({ username: "operator-1", password: "hunter2" })).toEqual({
      username: "operator-1",
      password: "hunter2",
    });
  });

  it("rejects a missing or empty username", () => {
    expectBffError(() => parseLoginCredentials({ password: "hunter2" }));
    expectBffError(() => parseLoginCredentials({ username: "  ", password: "hunter2" }));
  });

  it("rejects a missing or empty password", () => {
    expectBffError(() => parseLoginCredentials({ username: "operator-1" }));
    expectBffError(() => parseLoginCredentials({ username: "operator-1", password: "" }));
  });
});

describe("decodeJwtPayloadUnsafe", () => {
  it("decodes subject and roles from a well-formed token", () => {
    const token = `header.${base64UrlJson({ sub: "operator-1", roles: ["SECURITY_OPERATOR"] })}.signature`;
    expect(decodeJwtPayloadUnsafe(token)).toEqual({ subject: "operator-1", roles: ["SECURITY_OPERATOR"] });
  });

  it("rejects a token that is not three segments", () => {
    expectBffError(() => decodeJwtPayloadUnsafe("not-a-jwt"));
  });

  it("rejects a payload missing subject or roles", () => {
    expectBffError(() => decodeJwtPayloadUnsafe(`h.${base64UrlJson({ roles: ["SECURITY_OPERATOR"] })}.s`));
    expectBffError(() => decodeJwtPayloadUnsafe(`h.${base64UrlJson({ sub: "operator-1" })}.s`));
    expectBffError(() => decodeJwtPayloadUnsafe(`h.${base64UrlJson({ sub: "operator-1", roles: [] })}.s`));
  });

  it("rejects an unparseable payload segment", () => {
    expectBffError(() => decodeJwtPayloadUnsafe("h.not-base64-json.s"));
  });
});

describe("response body shapers never leak session internals", () => {
  it("login success body contains only subject, roles, and expiresAt", () => {
    const body = buildLoginSuccessBody(RECORD);
    expect(Object.keys(body).sort()).toEqual(["expiresAt", "roles", "subject"]);
    expect(JSON.stringify(body)).not.toContain(RECORD.backendToken);
    expect(JSON.stringify(body)).not.toContain(RECORD.sessionId);
    expect(JSON.stringify(body)).not.toContain(RECORD.csrfSecret);
  });

  it("status body for a valid session contains only subject, roles, expiresAt, and state flags", () => {
    const body = buildSessionStatusBody("valid", RECORD);
    expect(Object.keys(body).sort()).toEqual(["authenticated", "expiresAt", "roles", "state", "subject"]);
    expect(JSON.stringify(body)).not.toContain(RECORD.backendToken);
    expect(JSON.stringify(body)).not.toContain(RECORD.sessionId);
    expect(JSON.stringify(body)).not.toContain(RECORD.csrfSecret);
  });

  it("status body for an absent or expired session reveals nothing but the state", () => {
    expect(buildSessionStatusBody("absent")).toEqual({ authenticated: false, state: "absent" });
    expect(buildSessionStatusBody("expired")).toEqual({ authenticated: false, state: "expired" });
  });
});
