import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const scenarios = [
  {
    name: "overview",
    path: "/",
    heading: "Account protection at a glance",
  },
  {
    name: "decision investigation",
    path: "/decisions",
    heading: "Decision investigation",
  },
  {
    name: "recovery investigation",
    path: "/recoveries",
    heading: "Recovery investigation",
  },
  {
    name: "policy lifecycle",
    path: "/policies",
    heading: "Policy lifecycle",
  },
  {
    name: "outbox delivery",
    path: "/outbox",
    heading: "Outbox delivery",
  },
  {
    name: "design-system showcase",
    path: "/design-system",
    heading: "AccountShield console design system",
  },
  {
    name: "operator sign in",
    path: "/login",
    heading: "Operator sign in",
  },
] as const;

for (const scenario of scenarios) {
  test(`@a11y ${scenario.name} has no critical or serious axe violations`, async ({
    page,
  }) => {
    await page.goto(scenario.path);
    await expect(
      page.getByRole("heading", {
        level: 1,
        name: scenario.heading,
      }),
    ).toBeVisible();

    if (scenario.path === "/decisions") {
      await expect(
        page.getByRole("table", { name: "Decision investigation results" }),
      ).toBeVisible();
    }

    if (scenario.path === "/recoveries") {
      await expect(
        page.getByRole("table", { name: "Recovery investigation results" }),
      ).toBeVisible();
    }

    if (scenario.path === "/policies") {
      await expect(
        page.getByRole("table", { name: "Policy directory results" }),
      ).toBeVisible();
    }

    if (scenario.path === "/outbox") {
      await expect(
        page.getByRole("table", { name: "Outbox delivery records" }),
      ).toBeVisible();
    }

    const results = await new AxeBuilder({ page }).analyze();
    const blockingViolations = results.violations.filter(
      (violation) =>
        violation.impact === "critical" || violation.impact === "serious",
    );

    expect(
      blockingViolations,
      JSON.stringify(blockingViolations, null, 2),
    ).toEqual([]);
  });
}

test("@a11y policy rollout controls (percentage form and rollback confirmation) have no critical or serious axe violations", async ({
  page,
}) => {
  await page.goto("/policies");
  await expect(
    page.getByRole("table", { name: "Policy directory results" }),
  ).toBeVisible();

  const row = page.getByRole("row", { name: /credential-change-canary/ });
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(
    page.getByRole("heading", { level: 2, name: "Policy detail" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { level: 4, name: "Rollout controls" }),
  ).toBeVisible();

  await page.getByRole("button", { name: "Adjust percentage" }).click();
  await expect(page.getByLabel("New rollout percentage")).toBeVisible();

  const percentageFormResults = await new AxeBuilder({ page }).analyze();
  const percentageFormViolations = percentageFormResults.violations.filter(
    (violation) =>
      violation.impact === "critical" || violation.impact === "serious",
  );
  expect(
    percentageFormViolations,
    JSON.stringify(percentageFormViolations, null, 2),
  ).toEqual([]);

  // Reload to return to the idle stage -- "Roll back" only renders there, not while the
  // percentage-adjustment form (started above) is open.
  await page.reload();
  await row.getByRole("button", { name: "Investigate policy" }).click();
  await expect(
    page.getByRole("heading", { level: 4, name: "Rollout controls" }),
  ).toBeVisible();

  await page.getByRole("button", { name: "Roll back" }).click();
  await expect(
    page.getByText("Roll back immediately?", { exact: true }),
  ).toBeVisible();

  const rollbackResults = await new AxeBuilder({ page }).analyze();
  const rollbackViolations = rollbackResults.violations.filter(
    (violation) =>
      violation.impact === "critical" || violation.impact === "serious",
  );
  expect(
    rollbackViolations,
    JSON.stringify(rollbackViolations, null, 2),
  ).toEqual([]);
});

test("@a11y evidence export panel (reason form and verified result) have no critical or serious axe violations", async ({
  page,
}) => {
  await page.goto("/decisions");
  await expect(
    page.getByRole("table", { name: "Decision investigation results" }),
  ).toBeVisible();

  await page.getByRole("button", { name: "Export evidence" }).first().click();
  await expect(
    page.getByRole("heading", { level: 2, name: "Export evidence" }),
  ).toBeVisible();
  await expect(page.getByLabel("Reason for export")).toBeVisible();

  const reasonFormResults = await new AxeBuilder({ page }).analyze();
  const reasonFormViolations = reasonFormResults.violations.filter(
    (violation) =>
      violation.impact === "critical" || violation.impact === "serious",
  );
  expect(
    reasonFormViolations,
    JSON.stringify(reasonFormViolations, null, 2),
  ).toEqual([]);

  await page.getByLabel("Reason for export").fill("customer dispute review");
  await page.getByRole("button", { name: "Export bundle" }).click();
  await page.getByRole("button", { name: "Verify bundle" }).click();
  await expect(page.getByText("Bundle verified", { exact: true })).toBeVisible();

  const verifiedResults = await new AxeBuilder({ page }).analyze();
  const verifiedViolations = verifiedResults.violations.filter(
    (violation) =>
      violation.impact === "critical" || violation.impact === "serious",
  );
  expect(
    verifiedViolations,
    JSON.stringify(verifiedViolations, null, 2),
  ).toEqual([]);
});
