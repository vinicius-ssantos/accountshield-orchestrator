import { expect, test } from "@playwright/test";

test("operator can export and verify a signed evidence bundle without a URL reference", async ({
  page,
}) => {
  const decisionReference = "00000000-0000-4000-8000-000000000001";
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  await page.getByRole("button", { name: "Export evidence" }).first().click();

  await expect(page.getByRole("heading", { level: 2, name: "Export evidence" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Export bundle" })).toBeDisabled();

  await page.getByLabel("Reason for export").fill("customer dispute review");
  await expect(page.getByRole("button", { name: "Export bundle" })).toBeEnabled();
  await page.getByRole("button", { name: "Export bundle" }).click();

  await expect(page.getByText("customer dispute review", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Download bundle (JSON)" })).toBeVisible();

  await page.getByRole("button", { name: "Verify bundle" }).click();
  await expect(page.getByText("Bundle verified", { exact: true })).toBeVisible();

  await expect(page).toHaveURL(/\/decisions$/);
  await expect(page.locator("body")).not.toContainText(decisionReference);
});

test("operator can download the exported bundle as JSON", async ({ page }) => {
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  await page.getByRole("button", { name: "Export evidence" }).first().click();
  await page.getByLabel("Reason for export").fill("customer dispute review");
  await page.getByRole("button", { name: "Export bundle" }).click();
  await expect(page.getByRole("button", { name: "Download bundle (JSON)" })).toBeVisible();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download bundle (JSON)" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^evidence-.+\.json$/);
});

test("closing the evidence panel returns to the investigation queue", async ({ page }) => {
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  await page.getByRole("button", { name: "Export evidence" }).first().click();
  await expect(page.getByRole("heading", { level: 2, name: "Export evidence" })).toBeVisible();

  await page.getByRole("button", { name: "Close" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Export evidence" })).toHaveCount(0);
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();
});
