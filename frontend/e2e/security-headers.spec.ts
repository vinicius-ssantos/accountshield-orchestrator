import { expect, test } from "@playwright/test";

const INTERNAL_API_SENTINEL = "http://internal-api.invalid:8080";

function extractNonce(contentSecurityPolicy: string): string {
  const match = contentSecurityPolicy.match(/'nonce-([^']+)'/);
  expect(match, "CSP must contain a nonce source").not.toBeNull();
  return match?.[1] ?? "";
}

test("serves representative responses with restrictive security headers", async ({
  request,
}) => {
  const firstResponse = await request.get("/");
  expect(firstResponse.ok()).toBe(true);

  const headers = firstResponse.headers();
  const contentSecurityPolicy = headers["content-security-policy"] ?? "";
  const firstNonce = extractNonce(contentSecurityPolicy);

  expect(contentSecurityPolicy).toContain("default-src 'self'");
  expect(contentSecurityPolicy).toContain("'strict-dynamic'");
  expect(contentSecurityPolicy).toContain("frame-ancestors 'none'");
  expect(contentSecurityPolicy).toContain("object-src 'none'");
  expect(contentSecurityPolicy).not.toContain("'unsafe-eval'");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["referrer-policy"]).toBe("no-referrer");
  expect(headers["permissions-policy"]).toContain("camera=()");
  expect(headers["cache-control"]).toContain("no-store");
  expect(headers["x-robots-tag"]).toBe("noindex, nofollow, noarchive");
  expect(headers["strict-transport-security"]).toBeUndefined();

  const html = await firstResponse.text();
  expect(html).toContain(`nonce="${firstNonce}"`);
  expect(html).not.toContain(INTERNAL_API_SENTINEL);

  const scriptSources = Array.from(
    html.matchAll(/<script[^>]+src="([^"]+)"/g),
    (match) => match[1],
  );
  expect(scriptSources.length).toBeGreaterThan(0);

  for (const source of scriptSources) {
    const scriptResponse = await request.get(
      new URL(source, firstResponse.url()).toString(),
    );
    expect(scriptResponse.ok()).toBe(true);
    expect(await scriptResponse.text()).not.toContain(INTERNAL_API_SENTINEL);
  }

  const secondResponse = await request.get("/");
  const secondNonce = extractNonce(
    secondResponse.headers()["content-security-policy"] ?? "",
  );
  expect(secondNonce).not.toBe(firstNonce);

  const routeHandlerResponse = await request.get("/healthz");
  expect(routeHandlerResponse.ok()).toBe(true);
  expect(routeHandlerResponse.headers()["content-security-policy"]).toContain(
    "frame-ancestors 'none'",
  );
  expect(routeHandlerResponse.headers()["cache-control"]).toContain("no-store");
});

test("blocks an unauthorized inline script", async ({ page, request }) => {
  const applicationResponse = await request.get("/");
  const contentSecurityPolicy =
    applicationResponse.headers()["content-security-policy"] ?? "";
  expect(contentSecurityPolicy).toContain("'strict-dynamic'");

  await page.route("**/__csp-probe", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/html",
      headers: {
        "Content-Security-Policy": contentSecurityPolicy,
      },
      body: `<!doctype html>
        <html>
          <head>
            <script>
              document.documentElement.dataset.accountShieldCspBypassed = "true";
            </script>
          </head>
          <body>CSP probe</body>
        </html>`,
    });
  });

  await page.goto("/__csp-probe");

  await expect(page.locator("html")).not.toHaveAttribute(
    "data-account-shield-csp-bypassed",
    "true",
  );
});
