import { describe, expect, it } from "vitest";

import {
  buildSessionCookieValue,
  generateCsrfSecret,
  generateSessionId,
  signCsrfToken,
  verifyCsrfToken,
  verifySessionCookieValue,
} from "./session-crypto";

const SECRET = "unit-test-session-secret-value";

describe("session cookie HMAC", () => {
  it("round-trips a signed session ID", () => {
    const sessionId = generateSessionId();
    const cookieValue = buildSessionCookieValue(sessionId, SECRET);
    expect(verifySessionCookieValue(cookieValue, SECRET)).toBe(sessionId);
  });

  it("rejects a tampered session ID", () => {
    const sessionId = generateSessionId();
    const cookieValue = buildSessionCookieValue(sessionId, SECRET);
    const [, signature] = cookieValue.split(".");
    const tampered = `${sessionId}-tampered.${signature}`;
    expect(verifySessionCookieValue(tampered, SECRET)).toBeUndefined();
  });

  it("rejects a tampered signature", () => {
    const sessionId = generateSessionId();
    const cookieValue = buildSessionCookieValue(sessionId, SECRET);
    const tampered = `${cookieValue}xx`;
    expect(verifySessionCookieValue(tampered, SECRET)).toBeUndefined();
  });

  it("rejects a value signed with a different secret", () => {
    const sessionId = generateSessionId();
    const cookieValue = buildSessionCookieValue(sessionId, "another-secret-value");
    expect(verifySessionCookieValue(cookieValue, SECRET)).toBeUndefined();
  });

  it("never throws on malformed input", () => {
    expect(verifySessionCookieValue("", SECRET)).toBeUndefined();
    expect(verifySessionCookieValue("no-separator", SECRET)).toBeUndefined();
    expect(verifySessionCookieValue(".", SECRET)).toBeUndefined();
    expect(verifySessionCookieValue("a.", SECRET)).toBeUndefined();
  });

  it("generates distinct session IDs", () => {
    expect(generateSessionId()).not.toBe(generateSessionId());
  });
});

describe("CSRF token HMAC", () => {
  it("round-trips a valid token", () => {
    const sessionId = generateSessionId();
    const csrfSecret = generateCsrfSecret();
    const token = signCsrfToken(sessionId, csrfSecret, SECRET);
    expect(verifyCsrfToken(token, sessionId, csrfSecret, SECRET)).toBe(true);
  });

  it("rejects a token for a different session", () => {
    const csrfSecret = generateCsrfSecret();
    const token = signCsrfToken(generateSessionId(), csrfSecret, SECRET);
    expect(verifyCsrfToken(token, generateSessionId(), csrfSecret, SECRET)).toBe(false);
  });

  it("rejects a token for a different csrf secret", () => {
    const sessionId = generateSessionId();
    const token = signCsrfToken(sessionId, generateCsrfSecret(), SECRET);
    expect(verifyCsrfToken(token, sessionId, generateCsrfSecret(), SECRET)).toBe(false);
  });
});
