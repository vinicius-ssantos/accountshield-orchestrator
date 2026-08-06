import { expect, test } from "@playwright/test";

test("outbox delivery console shows health metrics and a mix of delivery states", async ({ page }) => {
  await page.goto("/outbox");

  await expect(page.getByRole("heading", { level: 1, name: "Outbox delivery" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Delivery health" })).toBeVisible();
  await expect(page.getByText("Pending", { exact: true })).toBeVisible();
  await expect(page.getByText("Retrying", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("In progress", { exact: true })).toBeVisible();
  await expect(page.getByText("Dead-lettered", { exact: true }).first()).toBeVisible();

  const table = page.getByRole("table", { name: "Outbox delivery records" });
  await expect(table).toBeVisible();
  await expect(table.getByText("ConnectException")).toBeVisible();
  await expect(table.getByText("Reason unavailable")).toBeVisible();
});

test("filtering by dead-lettered status narrows the delivery table", async ({ page }) => {
  await page.goto("/outbox");
  const table = page.getByRole("table", { name: "Outbox delivery records" });
  await expect(table).toBeVisible();

  await page.getByLabel("Statuses").selectOption("DEAD_LETTERED");
  await page.getByRole("button", { name: "Apply filters" }).click();

  await expect(page.getByRole("heading", { level: 2, name: "2 events" })).toBeVisible();
  await expect(table.getByText("Queued", { exact: true })).toHaveCount(0);
  await expect(table.getByText("Published", { exact: true })).toHaveCount(0);
});

test("no Replay, Delete, Skip, or Force Publish control is exposed", async ({ page }) => {
  await page.goto("/outbox");
  await expect(page.getByRole("table", { name: "Outbox delivery records" })).toBeVisible();

  for (const label of ["Replay", "Delete", "Skip", "Force Publish"]) {
    await expect(page.getByRole("button", { name: label, exact: true })).toHaveCount(0);
  }
});

test("Requeue is only offered for dead-lettered rows", async ({ page }) => {
  await page.goto("/outbox");
  const table = page.getByRole("table", { name: "Outbox delivery records" });
  await expect(table).toBeVisible();

  await page.getByLabel("Statuses").selectOption("PUBLISHED");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(table.getByRole("button", { name: "Requeue" })).toHaveCount(0);

  await page.getByLabel("Statuses").selectOption("DEAD_LETTERED");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(table.getByRole("button", { name: "Requeue" }).first()).toBeVisible();
});

test("operator can requeue a dead-lettered event through a lightweight confirmation, no step-up", async ({ page }) => {
  await page.goto("/outbox");
  const table = page.getByRole("table", { name: "Outbox delivery records" });
  await expect(table).toBeVisible();

  await page.getByLabel("Statuses").selectOption("DEAD_LETTERED");
  await page.getByRole("button", { name: "Apply filters" }).click();

  const row = table.getByRole("row", { name: /ConnectException/ });
  await row.getByRole("button", { name: "Requeue" }).click();

  await expect(row.getByText("Requeue this event now?")).toBeVisible();
  await row.getByRole("button", { name: "Confirm" }).click();

  await expect(row.getByText("requeued", { exact: true })).toBeVisible();
});

test("outbox filters are keyboard-operable", async ({ page }) => {
  await page.goto("/outbox");
  await expect(page.getByRole("table", { name: "Outbox delivery records" })).toBeVisible();

  const refreshButton = page.getByRole("button", { name: "Refresh" });
  await refreshButton.focus();
  await page.keyboard.press("Enter");

  await expect(page.getByRole("table", { name: "Outbox delivery records" })).toBeVisible();
});
