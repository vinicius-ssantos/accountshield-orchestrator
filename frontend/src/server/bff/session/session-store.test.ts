import { beforeEach, describe, expect, it } from "vitest";

import type { SessionRecord } from "./session-model";
import {
  clearSessionStoreForTesting,
  createSession,
  getSession,
  revokeSession,
  rotateSession,
  sessionStoreSizeForTesting,
  touchSession,
  updateBackendToken,
} from "./session-store";

const TTL_CONFIG = { absoluteTtlMs: 8 * 60 * 60 * 1000, inactivityTtlMs: 20 * 60 * 1000 };

function record(overrides: Partial<SessionRecord> = {}, now = Date.now()): SessionRecord {
  return {
    sessionId: overrides.sessionId ?? `session-${Math.random()}`,
    subject: "operator-1",
    roles: ["SECURITY_OPERATOR"],
    backendToken: "backend-token",
    backendTokenExpiresAt: now + 15 * 60 * 1000,
    csrfSecret: "csrf-secret",
    createdAt: now,
    lastSeenAt: now,
    absoluteExpiresAt: now + TTL_CONFIG.absoluteTtlMs,
    ...overrides,
  };
}

beforeEach(() => {
  clearSessionStoreForTesting();
});

describe("session store", () => {
  it("creates and retrieves a valid session", () => {
    const now = Date.now();
    const stored = createSession(record({}, now));
    const lookup = getSession(stored.sessionId, now, TTL_CONFIG);
    expect(lookup.state).toBe("valid");
    expect(lookup.record?.subject).toBe("operator-1");
  });

  it("reports absent for an unknown session ID", () => {
    const lookup = getSession("does-not-exist", Date.now(), TTL_CONFIG);
    expect(lookup.state).toBe("absent");
    expect(lookup.record).toBeUndefined();
  });

  it("expires and deletes a session past its absolute TTL", () => {
    const now = Date.now();
    const stored = createSession(record({ absoluteExpiresAt: now - 1 }, now));
    const lookup = getSession(stored.sessionId, now, TTL_CONFIG);
    expect(lookup.state).toBe("expired");
    expect(getSession(stored.sessionId, now, TTL_CONFIG).state).toBe("absent");
  });

  it("expires and deletes a session past its inactivity TTL even before absolute expiry", () => {
    const now = Date.now();
    const stored = createSession(record({ lastSeenAt: now - TTL_CONFIG.inactivityTtlMs - 1 }, now));
    const lookup = getSession(stored.sessionId, now, TTL_CONFIG);
    expect(lookup.state).toBe("expired");
  });

  it("slides lastSeenAt forward on touch without exceeding absolute expiry", () => {
    const now = Date.now();
    const stored = createSession(record({}, now));
    const later = now + 5 * 60 * 1000;
    touchSession(stored.sessionId, later);
    const lookup = getSession(stored.sessionId, later, TTL_CONFIG);
    expect(lookup.record?.lastSeenAt).toBe(later);
    expect(lookup.record?.absoluteExpiresAt).toBe(stored.absoluteExpiresAt);
  });

  it("updates the stored backend token in place", () => {
    const now = Date.now();
    const stored = createSession(record({}, now));
    updateBackendToken(stored.sessionId, "new-token", now + 30 * 60 * 1000);
    const lookup = getSession(stored.sessionId, now, TTL_CONFIG);
    expect(lookup.record?.backendToken).toBe("new-token");
    expect(lookup.record?.backendTokenExpiresAt).toBe(now + 30 * 60 * 1000);
  });

  it("revokes a session so it is no longer retrievable", () => {
    const now = Date.now();
    const stored = createSession(record({}, now));
    revokeSession(stored.sessionId);
    expect(getSession(stored.sessionId, now, TTL_CONFIG).state).toBe("absent");
  });

  it("rotation deletes the previous entry so its cookie value cannot be replayed", () => {
    const now = Date.now();
    const previous = createSession(record({}, now));
    const next = rotateSession(previous.sessionId, record({}, now));

    expect(getSession(previous.sessionId, now, TTL_CONFIG).state).toBe("absent");
    expect(getSession(next.sessionId, now, TTL_CONFIG).state).toBe("valid");
  });

  it("logout-then-logout is idempotent at the store layer", () => {
    const now = Date.now();
    const stored = createSession(record({}, now));
    revokeSession(stored.sessionId);
    expect(() => revokeSession(stored.sessionId)).not.toThrow();
    expect(sessionStoreSizeForTesting()).toBe(0);
  });
});
