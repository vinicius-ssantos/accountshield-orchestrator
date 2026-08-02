import { beforeEach, describe, expect, it, vi } from "vitest";

import { csrfCookieValue } from "./session/csrf";
import { buildSessionCookieValue } from "./session/session-crypto";
import type { SessionRecord } from "./session/session-model";
import { clearSessionStoreForTesting, createSession } from "./session/session-store";
import { handleRequeueRequest } from "./outbox-requeue";
import type { OutboxRequeueService } from "./outbox-requeue-core";

const ENV = { NEXT_PUBLIC_APP_ENV: "test" };
const LIVE_ENV = { NEXT_PUBLIC_APP_ENV: "test", ACCOUNTSHIELD_DATA_SOURCE: "live", ACCOUNTSHIELD_API_URL: "http://localhost:8080" };
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";
const EVENT_ID = "00000000-0000-4000-b000-000000000006";

function fakeService(overrides: Partial<OutboxRequeueService> = {}): OutboxRequeueService {
  return {
    requeue: vi.fn(async () => ({ requeued: true as const })),
    ...overrides,
  };
}

function storedSession(overrides: Partial<SessionRecord> = {}) {
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
    cookieValue: buildSessionCookieValue(record.sessionId, SECRET),
    csrfToken: csrfCookieValue(record.sessionId, record.csrfSecret, SECRET),
  };
}

function authorizedRequest(body: unknown, cookieValue: string, csrfToken: string): Request {
  return new Request(`${ORIGIN}/api/bff/outbox-requeue`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      cookie: `as_session=${cookieValue}`,
      "sec-fetch-site": "same-origin",
      "x-as-csrf-token": csrfToken,
    },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  clearSessionStoreForTesting();
});

describe("handleRequeueRequest", () => {
  it("requires an authenticated session", async () => {
    // No service injected -- this must exercise the real createOutboxRequeueClient path so the
    // session guard actually runs (a fake service would bypass it entirely).
    const response = await handleRequeueRequest(
      new Request(`${ORIGIN}/api/bff/outbox-requeue`, {
        method: "POST",
        headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: JSON.stringify({ eventId: EVENT_ID }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(401);
  });

  it("rejects a mutating request missing the CSRF header", async () => {
    const { cookieValue } = storedSession();
    const response = await handleRequeueRequest(
      new Request(`${ORIGIN}/api/bff/outbox-requeue`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
        },
        body: JSON.stringify({ eventId: EVENT_ID }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(403);
  });

  it("requeues the event for an authorized session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();
    const response = await handleRequeueRequest(
      authorizedRequest({ eventId: EVENT_ID }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ requeued: true });
    expect(service.requeue).toHaveBeenCalledWith({ eventId: EVENT_ID }, expect.any(String), expect.anything());
  });

  it("rejects a malformed body with 400, not 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleRequeueRequest(
      authorizedRequest({ eventId: "nope" }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(400);
  });

  it("passes an upstream not-dead-lettered conflict through as 409 without a 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const { BffError } = await import("./foundation");
    const service = fakeService({
      requeue: vi.fn(async () => {
        throw new BffError(
          "OUTBOX_EVENT_NOT_DEAD_LETTERED",
          409,
          "This outbox event is no longer dead-lettered and cannot be requeued.",
        );
      }),
    });
    const response = await handleRequeueRequest(
      authorizedRequest({ eventId: EVENT_ID }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(409);
    const body = await response.json();
    expect(body.code).toBe("OUTBOX_EVENT_NOT_DEAD_LETTERED");
  });
});
