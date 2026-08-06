import { beforeEach, describe, expect, it, vi } from "vitest";

import { csrfCookieValue } from "./session/csrf";
import { buildSessionCookieValue } from "./session/session-crypto";
import type { SessionRecord } from "./session/session-model";
import { clearSessionStoreForTesting, createSession } from "./session/session-store";
import { handleEvidenceExportRequest } from "./evidence-export";
import type { EvidenceExportService } from "./evidence-export-core";

const ENV = { NEXT_PUBLIC_APP_ENV: "test" };
const LIVE_ENV = {
  NEXT_PUBLIC_APP_ENV: "test",
  ACCOUNTSHIELD_DATA_SOURCE: "live",
  ACCOUNTSHIELD_API_URL: "http://localhost:8080",
};
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";
const PROTECTION_REQUEST_ID = "00000000-0000-4000-b000-000000000007";

const BUNDLE = {
  manifest: {
    bundleSchemaVersion: "evidence-bundle-1.0",
    decisionId: "00000000-0000-4000-a000-0000000000f1",
    protectionRequestId: PROTECTION_REQUEST_ID,
    generatedAt: "2026-08-01T09:00:00Z",
    exportedBy: "operator-1",
    exportReason: "customer dispute review",
    contentHashAlgorithm: "SHA-256",
    contentHash: "abc123",
    signatureAlgorithm: "SHA256withRSA",
    signature: "sig-abc",
    signingPublicKey: "pubkey-abc",
  },
  content: { decisionId: "00000000-0000-4000-a000-0000000000f1" },
};

function fakeService(overrides: Partial<EvidenceExportService> = {}): EvidenceExportService {
  return {
    export: vi.fn(async () => BUNDLE),
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
  return new Request(`${ORIGIN}/api/bff/evidence-export`, {
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

describe("handleEvidenceExportRequest", () => {
  it("requires an authenticated session", async () => {
    // No service injected -- this must exercise the real createEvidenceExportClient path so the
    // session guard actually runs (a fake service would bypass it entirely).
    const response = await handleEvidenceExportRequest(
      new Request(`${ORIGIN}/api/bff/evidence-export`, {
        method: "POST",
        headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: JSON.stringify({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(401);
  });

  it("rejects a mutating request missing the CSRF header", async () => {
    const { cookieValue } = storedSession();
    const response = await handleEvidenceExportRequest(
      new Request(`${ORIGIN}/api/bff/evidence-export`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
        },
        body: JSON.stringify({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(403);
  });

  it("exports the bundle for an authorized session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();
    const response = await handleEvidenceExportRequest(
      authorizedRequest({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual(BUNDLE);
    expect(service.export).toHaveBeenCalledWith(
      { protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" },
      expect.any(String),
      expect.anything(),
    );
  });

  it("rejects a malformed body with 400, not 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleEvidenceExportRequest(
      authorizedRequest({ protectionRequestId: "nope", reason: "review" }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(400);
  });

  it("passes an upstream not-found failure through as 404 without a 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const { BffError } = await import("./foundation");
    const service = fakeService({
      export: vi.fn(async () => {
        throw new BffError("NOT_FOUND", 404, "The protection request was not found.");
      }),
    });
    const response = await handleEvidenceExportRequest(
      authorizedRequest({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(404);
    const body = await response.json();
    expect(body.code).toBe("NOT_FOUND");
  });
});
