import { expect, test } from "@playwright/test";

test("operator can start a rollout for an approved candidate through fresh step-up", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /step-up-policy-approved/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await expect(page.getByRole("heading", { level: 4, name: "Rollout controls" })).toBeVisible();
  await page.getByRole("button", { name: "Start rollout" }).click();

  await expect(page.getByLabel("Candidate version")).toBeVisible();
  await expect(page.getByLabel("Candidate version")).toHaveValue("1.0.0");
  await page.getByLabel("Rollout percentage").fill("20");
  await page.getByRole("button", { name: "Continue to step-up" }).click();

  await expect(page.getByLabel("Simulated code")).toBeVisible();
  await expect(page.getByLabel("Simulated code")).not.toHaveValue("");
  await page.getByRole("button", { name: "Verify code" }).click();
  await expect(page.getByText("step-up verified", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Confirm start rollout" }).click();
  await expect(page.getByText("Rollout started", { exact: true })).toBeVisible();
});

test("operator can adjust an active rollout's percentage through fresh step-up", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /credential-change-canary/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await expect(page.getByRole("heading", { level: 4, name: "Rollout controls" })).toBeVisible();
  await page.getByRole("button", { name: "Adjust percentage" }).click();

  await page.getByLabel("New rollout percentage").fill("60");
  await page.getByRole("button", { name: "Continue to step-up" }).click();

  await expect(page.getByLabel("Simulated code")).toBeVisible();
  await page.getByRole("button", { name: "Verify code" }).click();
  await expect(page.getByText("step-up verified", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Confirm percentage update" }).click();
  await expect(page.getByText("Rollout percentage updated", { exact: true })).toBeVisible();
});

test("operator rolling back an active rollout sees a distinct, immediate, no-step-up confirmation", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /credential-change-canary/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await page.getByRole("button", { name: "Roll back" }).click();

  await expect(page.getByText("Roll back immediately?", { exact: true })).toBeVisible();
  await expect(page.getByText(/takes effect immediately/)).toBeVisible();

  await page.getByRole("button", { name: "Roll back now" }).click();
  await expect(page.getByText("Rollout rolled back", { exact: true })).toBeVisible();
});

test("no rollout controls are offered for a policy with no approved candidate and no active rollout", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /device-trust-draft/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await expect(page.getByRole("heading", { level: 4, name: "Rollout controls" })).toHaveCount(0);
});
