import { expect, test } from "@playwright/test";

const VALID_CORRELATION_ID = "browser.correlation-1234";

function expectSafeProblem(problem: Record<string, unknown>): void {
  expect(problem).toHaveProperty("type");
  expect(problem).toHaveProperty("title");
  expect(problem).toHaveProperty("status");
  expect(problem).toHaveProperty("code");
  expect(problem).toHaveProperty("correlationId");
  expect(problem).toHaveProperty("retryable");
  expect(problem).not.toHaveProperty("detail");
  expect(problem).not.toHaveProperty("stack");
  expect(problem).not.toHaveProperty("exception");
}

test("fixture runtime status traverses the narrow BFF boundary", async ({
  request,
}) => {
  const response = await request.get("/api/bff/runtime-status", {
    headers: { "x-correlation-id": VALID_CORRELATION_ID },
  });

  expect(response.ok()).toBe(true);
  expect(response.headers()["cache-control"]).toContain("no-store");
  expect(response.headers()["x-correlation-id"]).toBe(VALID_CORRELATION_ID);

  const body = await response.json();
  expect(body).toMatchObject({
    availability: "available",
    source: "fixtures",
    correlationId: VALID_CORRELATION_ID,
  });
  expect(new Date(body.checkedAt).toString()).not.toBe("Invalid Date");
  expect(Object.keys(body).sort()).toEqual(
    ["availability", "checkedAt", "correlationId", "source"].sort(),
  );
  expect(JSON.stringify(body)).not.toContain("http://app:8080");
  expect(JSON.stringify(body)).not.toContain("ACCOUNTSHIELD_API_URL");
});

test("invalid correlation IDs are replaced instead of forwarded", async ({
  request,
}) => {
  const response = await request.get("/api/bff/runtime-status", {
    headers: { "x-correlation-id": "invalid correlation with spaces" },
  });

  expect(response.ok()).toBe(true);
  const generated = response.headers()["x-correlation-id"];
  expect(generated).toMatch(/^bff_[a-f0-9]{32}$/);
  expect(generated).not.toContain("invalid correlation");
});

test("unsupported methods return stable Problem Details", async ({ request }) => {
  const response = await request.post("/api/bff/runtime-status", {
    headers: {
      "content-type": "application/json",
      "x-correlation-id": VALID_CORRELATION_ID,
    },
    data: { destinationUrl: "http://attacker.invalid" },
  });

  expect(response.status()).toBe(405);
  expect(response.headers()["allow"]).toBe("GET");
  expect(response.headers()["cache-control"]).toContain("no-store");
  expect(response.headers()["content-type"]).toContain(
    "application/problem+json",
  );

  const problem = (await response.json()) as Record<string, unknown>;
  expectSafeProblem(problem);
  expect(problem).toMatchObject({
    code: "METHOD_NOT_ALLOWED",
    status: 405,
    correlationId: VALID_CORRELATION_ID,
    retryable: false,
  });
  expect(JSON.stringify(problem)).not.toContain("attacker.invalid");
});
