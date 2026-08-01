import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchSessionStatus, login, logout, SessionBrowserError } from "./session-browser";

afterEach(() => {
  document.cookie = "as_csrf=; Max-Age=0; Path=/";
});

describe("fetchSessionStatus", () => {
  it("parses an authenticated status response", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({
          authenticated: true,
          state: "valid",
          subject: "operator-1",
          roles: ["SECURITY_OPERATOR"],
          expiresAt: "2026-08-01T00:00:00.000Z",
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );

    const result = await fetchSessionStatus({ fetchImplementation });

    expect(result).toEqual({
      authenticated: true,
      state: "valid",
      subject: "operator-1",
      roles: ["SECURITY_OPERATOR"],
      expiresAt: "2026-08-01T00:00:00.000Z",
    });
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/session/status");
    expect(init.credentials).toBe("same-origin");
  });

  it("parses an unauthenticated status response", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ authenticated: false, state: "absent" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(fetchSessionStatus({ fetchImplementation })).resolves.toEqual({
      authenticated: false,
      state: "absent",
    });
  });

  it("fails closed on a malformed response", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ authenticated: true }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(fetchSessionStatus({ fetchImplementation })).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});

describe("login", () => {
  it("posts credentials to the login endpoint", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ subject: "operator-1", roles: ["SECURITY_OPERATOR"], expiresAt: "x" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await login({ username: "operator-1", password: "accountshield-demo-operator" }, { fetchImplementation });

    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/session/login");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({
      username: "operator-1",
      password: "accountshield-demo-operator",
    });
  });

  it("surfaces a generic error without leaking the upstream detail", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ code: "INVALID_CREDENTIALS", detail: "raw backend detail" }), {
        status: 401,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    const promise = login({ username: "operator-1", password: "wrong" }, { fetchImplementation });

    await expect(promise).rejects.toBeInstanceOf(SessionBrowserError);
    await expect(promise).rejects.toMatchObject({ code: "INVALID_CREDENTIALS", status: 401 });
  });
});

describe("logout", () => {
  it("echoes the CSRF cookie value as a header", async () => {
    document.cookie = "as_csrf=csrf-token-value; Path=/";
    const fetchImplementation = vi.fn(async () => new Response(JSON.stringify({ loggedOut: true }), { status: 200 }));

    await logout({ fetchImplementation });

    const [, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers["x-as-csrf-token"]).toBe("csrf-token-value");
  });

  it("omits the CSRF header when no CSRF cookie is present", async () => {
    const fetchImplementation = vi.fn(async () => new Response(JSON.stringify({ loggedOut: true }), { status: 200 }));

    await logout({ fetchImplementation });

    const [, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers["x-as-csrf-token"]).toBeUndefined();
  });
});
