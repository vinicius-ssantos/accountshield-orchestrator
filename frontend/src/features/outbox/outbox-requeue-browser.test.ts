import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OutboxRequeueBrowserError, requeueOutboxEvent } from "./outbox-requeue-browser";

const EVENT_ID = "00000000-0000-4000-b000-000000000006";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "as_csrf=; Max-Age=0; Path=/";
});

describe("requeueOutboxEvent", () => {
  it("echoes the CSRF cookie as a header and resolves on success", async () => {
    document.cookie = "as_csrf=csrf-token-value";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(JSON.stringify({ requeued: true }), { status: 200 }));

    await requeueOutboxEvent(EVENT_ID);

    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/outbox-requeue");
    expect(init.headers["x-as-csrf-token"]).toBe("csrf-token-value");
    expect(init.credentials).toBe("same-origin");
    expect(JSON.parse(init.body)).toEqual({ eventId: EVENT_ID });
  });

  it("throws OutboxRequeueBrowserError with the upstream code on failure", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "OUTBOX_EVENT_NOT_DEAD_LETTERED" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    const rejection = requeueOutboxEvent(EVENT_ID);
    await expect(rejection).rejects.toBeInstanceOf(OutboxRequeueBrowserError);
    await expect(rejection).rejects.toMatchObject({ code: "OUTBOX_EVENT_NOT_DEAD_LETTERED", status: 409 });
  });

  it("throws a MALFORMED_RESPONSE error when the failure body isn't valid JSON", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response("not json", { status: 500 }));

    await expect(requeueOutboxEvent(EVENT_ID)).rejects.toMatchObject({ code: "MALFORMED_RESPONSE", status: 500 });
  });
});
