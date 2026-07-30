import { expect, test } from "@playwright/test";

test("recovery investigation keeps filters and references out of the URL", async ({ page }) => {
  await page.goto("/recoveries");

  await expect(
    page.getByRole("heading", { level: 1, name: "Recovery investigation" }),
  ).toBeVisible();
  await expect(page.getByRole("table", { name: "Recovery investigation results" })).toBeVisible();

  await page.getByLabel("Status").selectOption("MANUAL_REVIEW");
  await page.getByRole("button", { name: "Apply filters" }).click();

  await expect(page).toHaveURL(/\/recoveries$/);
  await expect(page.getByText("1 recovery", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Investigate recovery" }).click();
  await expect(page).toHaveURL(/\/recoveries$/);
  await expect(page.getByRole("heading", { level: 2, name: "Recovery detail" })).toBeVisible();
  await expect(page.getByText("UNAVAILABLE", { exact: true })).toBeVisible();
});

test("recovery investigation supports keyboard-visible form submission", async ({ page }) => {
  await page.goto("/recoveries");
  await expect(page.getByRole("table", { name: "Recovery investigation results" })).toBeVisible();

  await page.getByLabel("Status").selectOption("DELAYED");
  await page.getByRole("button", { name: "Apply filters" }).focus();
  await page.keyboard.press("Enter");

  await expect(page).toHaveURL(/\/recoveries$/);
  await expect(page.getByText("1 recovery", { exact: true })).toBeVisible();
});
