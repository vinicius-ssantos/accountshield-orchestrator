import { describe, expect, it } from "vitest";

import {
  createContentSecurityPolicy,
  createStaticSecurityHeaders,
} from "./headers";

describe("createContentSecurityPolicy", () => {
  it("creates a nonce-based production policy without unsafe-eval", () => {
    const policy = createContentSecurityPolicy({
      nonce: "abc123DEF456",
      development: false,
      productionLike: true,
    });

    expect(policy).toContain("default-src 'self'");
    expect(policy).toContain(
      "script-src 'self' 'nonce-abc123DEF456' 'strict-dynamic'",
    );
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("upgrade-insecure-requests");
    expect(policy).not.toContain("'unsafe-eval'");
  });

  it("limits development exceptions to eval and websocket connectivity", () => {
    const policy = createContentSecurityPolicy({
      nonce: "developmentNonce",
      development: true,
      productionLike: false,
    });

    expect(policy).toContain("'unsafe-eval'");
    expect(policy).toContain("connect-src 'self' ws: wss:");
    expect(policy).not.toContain("upgrade-insecure-requests");
  });

  it("rejects malformed nonces", () => {
    expect(() =>
      createContentSecurityPolicy({
        nonce: "invalid nonce;",
        development: false,
        productionLike: false,
      }),
    ).toThrow("CSP nonce contains unsupported characters");
  });
});

describe("createStaticSecurityHeaders", () => {
  it("sets the common browser and sensitive-cache baseline", () => {
    const headers = new Map(
      createStaticSecurityHeaders("ci").map(({ key, value }) => [key, value]),
    );

    expect(headers.get("Cache-Control")).toBe(
      "private, no-store, max-age=0, must-revalidate",
    );
    expect(headers.get("X-Frame-Options")).toBe("DENY");
    expect(headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(headers.get("Permissions-Policy")).toContain("camera=()");
    expect(headers.get("X-Robots-Tag")).toBe(
      "noindex, nofollow, noarchive",
    );
    expect(headers.has("Strict-Transport-Security")).toBe(false);
  });

  it("uses bounded HSTS in preview and preload HSTS only in production", () => {
    const preview = new Map(
      createStaticSecurityHeaders("preview").map(({ key, value }) => [
        key,
        value,
      ]),
    );
    const production = new Map(
      createStaticSecurityHeaders("production").map(({ key, value }) => [
        key,
        value,
      ]),
    );

    expect(preview.get("Strict-Transport-Security")).toBe(
      "max-age=31536000",
    );
    expect(production.get("Strict-Transport-Security")).toBe(
      "max-age=63072000; includeSubDomains; preload",
    );
  });
});
