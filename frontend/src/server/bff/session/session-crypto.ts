// Hand-rolled HMAC primitives over node:crypto -- no JWT/session library dependency.
// Deliberately does not import the literal "server-only" package: the real architecture
// boundary (ARCH001) is enforced by this file's path under src/server/, which the
// architecture-check script treats as server-only unconditionally; the npm package is a
// redundant runtime guard that would also make this file unimportable from a vitest test
// (jsdom sets `window`, which the package treats as a client bundle and throws on import).
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

const SESSION_ID_BYTES = 32;
const CSRF_SECRET_BYTES = 24;

export function generateSessionId(): string {
  return randomBytes(SESSION_ID_BYTES).toString("base64url");
}

export function generateCsrfSecret(): string {
  return randomBytes(CSRF_SECRET_BYTES).toString("base64url");
}

function hmac(secret: string, value: string): string {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

function constantTimeEquals(expected: string, actual: string): boolean {
  const expectedBuffer = Buffer.from(expected);
  const actualBuffer = Buffer.from(actual);
  if (expectedBuffer.length !== actualBuffer.length) {
    return false;
  }
  return timingSafeEqual(expectedBuffer, actualBuffer);
}

export function buildSessionCookieValue(sessionId: string, secret: string): string {
  return `${sessionId}.${hmac(secret, sessionId)}`;
}

/**
 * Verifies an opaque session cookie value and returns the session ID it encodes, or
 * `undefined` if the value is malformed or its signature does not match. Never throws on
 * attacker-controlled input.
 */
export function verifySessionCookieValue(value: string, secret: string): string | undefined {
  const separatorIndex = value.indexOf(".");
  if (separatorIndex <= 0 || separatorIndex === value.length - 1) {
    return undefined;
  }
  const sessionId = value.slice(0, separatorIndex);
  const signature = value.slice(separatorIndex + 1);
  return constantTimeEquals(hmac(secret, sessionId), signature) ? sessionId : undefined;
}

export function signCsrfToken(sessionId: string, csrfSecret: string, secret: string): string {
  return hmac(secret, `${sessionId}:${csrfSecret}`);
}

export function verifyCsrfToken(
  token: string,
  sessionId: string,
  csrfSecret: string,
  secret: string,
): boolean {
  return constantTimeEquals(signCsrfToken(sessionId, csrfSecret, secret), token);
}
