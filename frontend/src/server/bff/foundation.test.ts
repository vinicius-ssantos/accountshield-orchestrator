import { describe, expect, it } from "vitest";

import {
  BffError,
  assertRequestPolicy,
  createSafeLogRecord,
  isValidCorrelationId,
  readJsonObject,
  resolveCorrelationId,
  toProblemDetails,
} from "./foundation";

function captureError(callback: () => void): BffError {
  try {
    callback();
  } catch (error) {
    expect(error).toBeInstanceOf(BffError);
    return error as BffError;
  }
  throw new Error("Expected callback to throw");
}

describe("correlation ID policy", () => {
  it("preserves only validated inbound IDs", () => {
    expect(resolveCorrelationId("browser.trace-1234", () => "generated-id")).toBe(
      "browser.trace-1234",
    );
    expect(isValidCorrelationId("browser.trace-1234")).toBe(true);
  });

  it("replaces malformed or oversized IDs", () => {
    expect(resolveCorrelationId("bad id", () => "generated-id")).toBe(
      "generated-id",
    );
    expect(resolveCorrelationId("x".repeat(129), () => "generated-id")).toBe(
      "generated-id",
    );
  });
});

describe("request policy", () => {
  it("rejects unsupported methods", () => {
    const error = captureError(() =>
      assertRequestPolicy(
        new Request("http://localhost/api/bff/runtime-status", {
          method: "POST",
        }),
        { allowedMethods: ["GET"], maxBodyBytes: 0 },
      ),
    );

    expect(error).toMatchObject({ code: "METHOD_NOT_ALLOWED", status: 405 });
  });

  it("rejects unsupported content types", () => {
    const error = captureError(() =>
      assertRequestPolicy(
        new Request("http://localhost/api/bff/example", {
          method: "POST",
          headers: {
            "content-length": "2",
            "content-type": "text/plain",
          },
          body: "{}",
        }),
        {
          allowedMethods: ["POST"],
          allowedContentTypes: ["application/json"],
          maxBodyBytes: 128,
        },
      ),
    );

    expect(error).toMatchObject({
      code: "UNSUPPORTED_MEDIA_TYPE",
      status: 415,
    });
  });

  it("rejects declared and actual oversized payloads", async () => {
    const declaredError = captureError(() =>
      assertRequestPolicy(
        new Request("http://localhost/api/bff/example", {
          method: "POST",
          headers: {
            "content-length": "129",
            "content-type": "application/json",
          },
          body: "{}",
        }),
        {
          allowedMethods: ["POST"],
          allowedContentTypes: ["application/json"],
          maxBodyBytes: 128,
        },
      ),
    );
    expect(declaredError).toMatchObject({
      code: "PAYLOAD_TOO_LARGE",
      status: 413,
    });

    await expect(
      readJsonObject(
        new Request("http://localhost/api/bff/example", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ value: "x".repeat(64) }),
        }),
        16,
      ),
    ).rejects.toMatchObject({ code: "PAYLOAD_TOO_LARGE", status: 413 });
  });
});

describe("safe errors and logs", () => {
  it("normalizes unknown errors without exposing internal details", () => {
    const problem = toProblemDetails(
      new Error("postgres.internal:5432 account acct_raw_secret"),
      "corr_safe_123",
    );

    expect(problem).toMatchObject({
      code: "INTERNAL_ERROR",
      status: 500,
      correlationId: "corr_safe_123",
      retryable: false,
    });
    expect(JSON.stringify(problem)).not.toContain("postgres.internal");
    expect(JSON.stringify(problem)).not.toContain("acct_raw_secret");
  });

  it("redacts tokens, secrets, challenges and raw identifiers", () => {
    const record = createSafeLogRecord("bff.test", "corr_safe_123", {
      authorization: "Bearer token-value",
      refreshToken: "refresh-value",
      challenge: "challenge-value",
      accountId: "acct_raw_secret",
      nested: {
        message: "request used Bearer abc.def.ghi",
        safeCount: 2,
      },
    });
    const serialized = JSON.stringify(record);

    expect(serialized).toContain("[REDACTED]");
    expect(serialized).not.toContain("token-value");
    expect(serialized).not.toContain("refresh-value");
    expect(serialized).not.toContain("challenge-value");
    expect(serialized).not.toContain("acct_raw_secret");
    expect(serialized).toContain("safeCount");
  });
});
