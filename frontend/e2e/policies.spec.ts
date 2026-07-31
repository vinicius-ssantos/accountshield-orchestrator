import { expect, test } from "@playwright/test";

test("policy directory lists policies and keeps investigation off the URL", async ({ page }) => {
  await page.goto("/policies");

  await expect(
    page.getByRole("heading", { level: 1, name: "Policy lifecycle" }),
  ).toBeVisible();
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();
  await expect(page.getByText("account-protection-default")).toBeVisible();

  const row = page.getByRole("row", { name: /credential-change-canary/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();

  await expect(page).toHaveURL(/\/policies$/);
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 3, name: "Version history" })).toBeVisible();
});

test("canary policy shows an available impact analysis with transition and segment data", async ({
  page,
}) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /credential-change-canary/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();

  await expect(page.getByRole("heading", { level: 3, name: "Active rollout" })).toBeVisible();
  await expect(page.getByText("2.0.0", { exact: true }).first()).toBeVisible();
  const impactSection = page.locator("section", { has: page.getByRole("heading", { level: 3, name: "Historical impact analysis" }) });
  await expect(impactSection.getByText("AVAILABLE", { exact: true })).toBeVisible();
  await expect(page.getByRole("table", { name: "Impact by event type" })).toBeVisible();
  await expect(page.getByRole("table", { name: "Impact by risk band" })).toBeVisible();
});

test("a policy with no active rollout shows impact analysis as not applicable", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /^account-protection-default/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();

  const impactSection = page.locator("section", { has: page.getByRole("heading", { level: 3, name: "Historical impact analysis" }) });
  await expect(impactSection.getByText("NOT APPLICABLE", { exact: true })).toBeVisible();
  await expect(
    page.getByText("No active rollout, so there is no candidate to compare against.", { exact: true }),
  ).toBeVisible();
});

test("an invalid draft shows its blocking analyzer diagnostic", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /sensitive-action-invalid/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();

  await expect(page.getByText("STEP UP MAX SCORE MISSING", { exact: true })).toBeVisible();
});

test("policy directory keyboard-visible navigation reaches the investigation panel", async ({
  page,
}) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const button = page.getByRole("button", { name: "Investigate policy" }).first();
  await button.focus();
  await page.keyboard.press("Enter");

  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();
  await expect(page).toHaveURL(/\/policies$/);
});
