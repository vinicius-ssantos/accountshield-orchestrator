import { describe, expect, it } from "vitest";

import { CSRF_HEADER_NAME, csrfCookieValue, isCsrfTokenValid, isTrustedOrigin } from "./csrf";

const SECRET = "unit-test-session-secret-value";
const SESSION_ID = "session-id";
const CSRF_SECRET = "csrf-secret";

function requestWithHeader(
  headerValue: string | undefined,
  method = "POST",
): Request {
  const headers = new Headers();
  if (headerValue !== undefined) headers.set(CSRF_HEADER_NAME, headerValue);
  return new Request("https://console.example/api/bff/session/logout", { method, headers });
}

describe("isCsrfTokenValid", () => {
  it("accepts the token that matches the cookie value", () => {
    const token = csrfCookieValue(SESSION_ID, CSRF_SECRET, SECRET);
    expect(isCsrfTokenValid(requestWithHeader(token), SESSION_ID, CSRF_SECRET, SECRET)).toBe(true);
  });

  it("rejects a missing header", () => {
    expect(isCsrfTokenValid(requestWithHeader(undefined), SESSION_ID, CSRF_SECRET, SECRET)).toBe(false);
  });

  it("rejects a token for a different session", () => {
    const token = csrfCookieValue(SESSION_ID, CSRF_SECRET, SECRET);
    expect(isCsrfTokenValid(requestWithHeader(token), "different-session", CSRF_SECRET, SECRET)).toBe(false);
  });

  it("rejects a token replayed from a different (rotated) session's csrf secret", () => {
    const token = csrfCookieValue(SESSION_ID, CSRF_SECRET, SECRET);
    expect(isCsrfTokenValid(requestWithHeader(token), SESSION_ID, "rotated-csrf-secret", SECRET)).toBe(false);
  });
});

describe("isTrustedOrigin", () => {
  const selfOrigin = "https://console.example";

  it("trusts same-origin via Sec-Fetch-Site", () => {
    const request = new Request("https://console.example/api/bff/session/logout", {
      method: "POST",
      headers: { "sec-fetch-site": "same-origin" },
    });
    expect(isTrustedOrigin(request, selfOrigin)).toBe(true);
  });

  it("rejects cross-site via Sec-Fetch-Site", () => {
    const request = new Request("https://console.example/api/bff/session/logout", {
      method: "POST",
      headers: { "sec-fetch-site": "cross-site" },
    });
    expect(isTrustedOrigin(request, selfOrigin)).toBe(false);
  });

  it("falls back to a matching Origin header when Sec-Fetch-Site is absent", () => {
    const request = new Request("https://console.example/api/bff/session/logout", {
      method: "POST",
      headers: { origin: "https://console.example" },
    });
    expect(isTrustedOrigin(request, selfOrigin)).toBe(true);
  });

  it("rejects a mismatched Origin header", () => {
    const request = new Request("https://console.example/api/bff/session/logout", {
      method: "POST",
      headers: { origin: "https://attacker.example" },
    });
    expect(isTrustedOrigin(request, selfOrigin)).toBe(false);
  });
});
