import { expect, test } from "@playwright/test";

test("decision investigation keeps filters out of the URL", async ({ page }) => {
  await page.goto("/decisions");

  await expect(
    page.getByRole("heading", { level: 1, name: "Decision investigation" }),
  ).toBeVisible();
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  const correlationId = "corr_demo_login_8f12";
  await page.getByRole("searchbox", { name: "Correlation ID" }).fill(correlationId);
  await page.getByRole("button", { name: "Apply filters" }).click();

  await expect(page).toHaveURL(/\/decisions$/);
  await expect(page.getByText("1 decision", { exact: true })).toBeVisible();
  await expect(page.getByText("corr_demo_login_8f12")).toHaveCount(0);
  await expect(page.getByLabel("Masked correlation ID")).toContainText("corr_d");
});

test("decision investigation supports keyboard-visible form submission", async ({ page }) => {
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  await page.getByLabel("Risk band").selectOption("HIGH");
  await page.getByRole("button", { name: "Apply filters" }).focus();
  await page.keyboard.press("Enter");

  await expect(page).toHaveURL(/\/decisions$/);
  await expect(page.getByText("2 decisions", { exact: true })).toBeVisible();
});
