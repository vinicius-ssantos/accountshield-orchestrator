import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import { AccountShieldOutboxRequeueClient, parseRequeueInput } from "./outbox-requeue-core";

const EVENT_ID = "00000000-0000-4000-b000-000000000006";

describe("parseRequeueInput", () => {
  it("accepts a valid eventId", () => {
    expect(parseRequeueInput({ eventId: EVENT_ID })).toEqual({ eventId: EVENT_ID });
  });

  it("rejects a non-UUID eventId", () => {
    expect(() => parseRequeueInput({ eventId: "not-a-uuid" })).toThrow(BffError);
  });

  it("rejects a missing eventId", () => {
    expect(() => parseRequeueInput({})).toThrow(BffError);
  });
});

describe("AccountShieldOutboxRequeueClient", () => {
  const client = new AccountShieldOutboxRequeueClient({
    origin: "http://localhost:8080",
    operatorToken: "operator-token",
    timeoutMs: 1000,
  });

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts to the requeue endpoint and treats 204 No Content as success", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 204 }));

    const result = await client.requeue({ eventId: EVENT_ID }, "corr-1");

    expect(result).toEqual({ requeued: true });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/outbox/${EVENT_ID}/requeue`);
    expect(init.method).toBe("POST");
    expect(init.headers.authorization).toBe("Bearer operator-token");
  });

  it("maps OUTBOX_EVENT_NOT_DEAD_LETTERED to a distinct, explainable BffError code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "OUTBOX_EVENT_NOT_DEAD_LETTERED" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(client.requeue({ eventId: EVENT_ID }, "corr-1")).rejects.toMatchObject({
      code: "OUTBOX_EVENT_NOT_DEAD_LETTERED",
      status: 409,
    });
  });

  it("maps OUTBOX_EVENT_NOT_FOUND to NOT_FOUND", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "OUTBOX_EVENT_NOT_FOUND" }), {
        status: 404,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(client.requeue({ eventId: EVENT_ID }, "corr-1")).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
    });
  });

  it("maps a 401 upstream failure to UNAUTHORIZED", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 401 }));

    await expect(client.requeue({ eventId: EVENT_ID }, "corr-1")).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      status: 401,
    });
  });

  it("maps a 403 upstream failure to FORBIDDEN", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 403 }));

    await expect(client.requeue({ eventId: EVENT_ID }, "corr-1")).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
    });
  });

  it("never leaks the backend token in a thrown error message", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError("network error"));

    try {
      await client.requeue({ eventId: EVENT_ID }, "corr-1");
      expect.unreachable();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      expect(message).not.toContain("operator-token");
    }
  });
});
