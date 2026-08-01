import { expect, test } from "@playwright/test";

test("operator can approve a validated policy version through fresh step-up", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /recovery-policy-pending-approval/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await expect(page.getByRole("heading", { level: 4, name: "Manage 1.0.0" })).toBeVisible();
  await page.getByRole("button", { name: "Approve" }).click();

  await page.getByLabel("Reason for approval").fill("quarterly policy review");
  await page.getByRole("button", { name: "Continue to step-up" }).click();

  await expect(page.getByLabel("Simulated code")).toBeVisible();
  await expect(page.getByLabel("Simulated code")).not.toHaveValue("");
  await page.getByRole("button", { name: "Verify code" }).click();
  await expect(page.getByText("step-up verified", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Confirm approve" }).click();
  await expect(page.getByText("Policy version approved", { exact: true })).toBeVisible();
});

test("operator can reject a draft policy version without step-up", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  const row = page.getByRole("row", { name: /device-trust-draft/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await expect(page.getByRole("heading", { level: 4, name: "Manage 0.1.0" })).toBeVisible();
  await page.getByRole("button", { name: "Reject" }).click();

  await expect(page.getByText("Policy version rejected", { exact: true })).toBeVisible();
});

test("no lifecycle action is offered for a policy version in a terminal state", async ({ page }) => {
  await page.goto("/policies");
  await expect(page.getByRole("table", { name: "Policy directory results" })).toBeVisible();

  // REJECTED is terminal per ADR 0007 -- no approve/activate/reject/retire transition is legal
  // from it, so no "Manage" section should render.
  const row = page.getByRole("row", { name: /webhook-policy-rejected/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Policy detail" })).toBeVisible();

  await expect(page.getByRole("heading", { level: 4, name: /^Manage/ })).toHaveCount(0);
});
