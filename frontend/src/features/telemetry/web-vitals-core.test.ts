import { describe, expect, it } from "vitest";

import { classifyTelemetryRoute, normalizeWebVital } from "./web-vitals-core";

describe("Web Vitals privacy contract", () => {
  it.each([
    ["/", "home"],
    ["/decisions/decision-123", "decisions"],
    ["/recovery/reference-456", "recovery"],
    ["/unexpected/private-value", "unknown"],
  ] as const)("maps %s to a bounded route category", (pathname, expected) => {
    expect(classifyTelemetryRoute(pathname)).toBe(expected);
  });

  it("accepts and rounds an allowlisted metric", () => {
    expect(normalizeWebVital({
      name: "LCP",
      value: 1234.56789,
      rating: "needs-improvement",
      navigationType: "navigate",
      route: "decisions",
    })).toEqual({
      name: "LCP",
      value: 1234.568,
      rating: "needs-improvement",
      navigationType: "navigate",
      route: "decisions",
    });
  });

  it.each([
    { name: "custom.metric", value: 1, rating: "good", navigationType: "navigate", route: "home" },
    { name: "CLS", value: -1, rating: "good", navigationType: "navigate", route: "home" },
    { name: "CLS", value: 1, rating: "good", navigationType: "navigate", route: "/account/123" },
    { name: "CLS", value: 1, rating: "good", navigationType: "navigate", route: "home", id: "secret-id" },
    { name: "CLS", value: 1, rating: "good", navigationType: "navigate", route: "home", url: "https://example.test/private" },
  ])("rejects unbounded or sensitive payloads", (payload) => {
    expect(normalizeWebVital(payload)).toBeNull();
  });
});
