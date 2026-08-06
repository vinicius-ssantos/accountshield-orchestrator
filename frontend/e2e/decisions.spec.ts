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

test("authorized operator can explain a decision without exposing its reference", async ({
  page,
}) => {
  const decisionReference = "00000000-0000-4000-8000-000000000001";
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  const investigate = page.getByRole("button", { name: "Investigate decision" }).first();
  await investigate.focus();
  await page.keyboard.press("Enter");

  await expect(
    page.getByRole("heading", { level: 2, name: "Decision explanation" }),
  ).toBeVisible();
  await expect(page.getByRole("list", { name: "Decision event timeline" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 3, name: "Risk reasons" })).toBeVisible();
  await expect(page.getByText("risk-score-v3", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/decisions$/);
  await expect(page.locator("body")).not.toContainText(decisionReference);
});

test("operator can compare a replay and see field-level divergence without a URL reference", async ({
  page,
}) => {
  const decisionReference = "00000000-0000-4000-8000-000000000002";
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  const divergedRow = page.getByRole("row", { name: /a921/ });
  await divergedRow.getByRole("button", { name: "Compare replay" }).click();

  await expect(page.getByRole("heading", { level: 2, name: "Replay comparison" })).toBeVisible();
  await expect(page.getByText("diverged", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("heading", { level: 3, name: "Divergence detail" })).toBeVisible();
  await expect(page).toHaveURL(/\/decisions$/);
  await expect(page.locator("body")).not.toContainText(decisionReference);
});

test("an unresolvable replay is shown explicitly, never as a fabricated match", async ({
  page,
}) => {
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  const unavailableRow = page.getByRole("row", { name: /120c/ });
  await unavailableRow.getByRole("button", { name: "Compare replay" }).click();

  await expect(
    page.getByText("Decision replay was not found", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("exact match", { exact: true })).toHaveCount(0);
  await expect(page.getByText("diverged", { exact: true })).toHaveCount(0);
  await expect(page).toHaveURL(/\/decisions$/);
});

test("degraded investigation distinguishes stale and unavailable evidence", async ({ page }) => {
  await page.goto("/decisions");
  await expect(page.getByRole("table", { name: "Decision investigation results" })).toBeVisible();

  await page
    .getByRole("searchbox", { name: "Correlation ID" })
    .fill("corr_demo_password_a921");
  await page.getByRole("button", { name: "Apply filters" }).click();

  await expect(page.getByText("1 decision", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Investigate decision" }).click();

  await expect(page.getByText("Partial or degraded evidence", { exact: true })).toBeVisible();
  const signalProvenance = page.getByRole("region", { name: "Signal provenance" });
  await expect(signalProvenance.getByText("STALE", { exact: true })).toBeVisible();
  await expect(signalProvenance.getByText("LOW", { exact: true })).toBeVisible();
  await expect(signalProvenance.getByText("integrity unavailable", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/decisions$/);
});
