import { expect, test } from "@playwright/test";

test("renders the deterministic read-only operations overview", async ({ page }) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "Account protection at a glance",
    }),
  ).toBeVisible();
  await expect(page.getByText("Fixture mode · no administrative mutations")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Primary navigation" })).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Recent account-protection decisions" }),
  ).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Correlation" })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Outcome" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Search correlation ID" })).toHaveAttribute(
    "href",
    "/decisions",
  );
});
