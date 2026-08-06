// In-memory, single-process session store -- the same "documented single-instance limitation"
// precedent as LocalJwtKeys and the in-memory rate limiter on the backend (ADR 0008, ADR 0011).
// Sessions do not survive a restart or scale-out to multiple Next.js instances.
import type { SessionLookupResult, SessionRecord } from "./session-model";

export interface SessionTtlConfig {
  absoluteTtlMs: number;
  inactivityTtlMs: number;
}

const store = new Map<string, SessionRecord>();

export function createSession(record: SessionRecord): SessionRecord {
  store.set(record.sessionId, record);
  return record;
}

export function getSession(sessionId: string, now: number, config: SessionTtlConfig): SessionLookupResult {
  const record = store.get(sessionId);
  if (!record) {
    return { state: "absent", record: undefined };
  }
  if (now >= record.absoluteExpiresAt || now - record.lastSeenAt > config.inactivityTtlMs) {
    store.delete(sessionId);
    return { state: "expired", record: undefined };
  }
  return { state: "valid", record };
}

export function touchSession(sessionId: string, now: number): void {
  const record = store.get(sessionId);
  if (record) {
    record.lastSeenAt = now;
  }
}

export function updateBackendToken(
  sessionId: string,
  backendToken: string,
  backendTokenExpiresAt: number,
): void {
  const record = store.get(sessionId);
  if (record) {
    record.backendToken = backendToken;
    record.backendTokenExpiresAt = backendTokenExpiresAt;
  }
}

/** Rotates a session: creates a new record and immediately deletes the old one, so a
 * pre-rotation cookie value cannot be replayed even if it leaked before rotation. */
export function rotateSession(previousSessionId: string, next: SessionRecord): SessionRecord {
  store.delete(previousSessionId);
  return createSession(next);
}

export function revokeSession(sessionId: string): void {
  store.delete(sessionId);
}

export function sessionStoreSizeForTesting(): number {
  return store.size;
}

export function clearSessionStoreForTesting(): void {
  store.clear();
}
