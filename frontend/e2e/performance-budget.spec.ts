import { expect, test } from "@playwright/test";

const ROUTES = ["/", "/decisions", "/policies", "/recoveries", "/outbox"];

test("core routes report no poor Web Vitals ratings in fixtures mode", async ({ page }) => {
  const reportedMetrics: Array<{ route: unknown; name: unknown; rating: unknown }> = [];

  await page.route("**/api/bff/telemetry/web-vitals", async (route) => {
    try {
      const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
      if (typeof body.name === "string" && typeof body.rating === "string") {
        reportedMetrics.push({ route: body.route, name: body.name, rating: body.rating });
      }
    } catch {
      // Malformed bodies are not this test's concern -- the BFF route's own tests cover that.
    }
    await route.continue();
  });

  for (const path of ROUTES) {
    await page.goto(path);
    await page.waitForLoadState("networkidle");
  }

  // Some metrics (notably CLS) only finalize and report on pagehide/visibilitychange -- navigate
  // away once at the end so the web-vitals library flushes anything still pending.
  await page.goto("about:blank");
  await page.waitForTimeout(200);

  const poorRatings = reportedMetrics.filter((metric) => metric.rating === "poor");
  expect(poorRatings, JSON.stringify(poorRatings, null, 2)).toEqual([]);
  expect(reportedMetrics.length).toBeGreaterThan(0);
});
