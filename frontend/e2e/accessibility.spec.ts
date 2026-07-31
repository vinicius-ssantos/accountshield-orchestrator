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
    name: "design-system showcase",
    path: "/design-system",
    heading: "AccountShield console design system",
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
