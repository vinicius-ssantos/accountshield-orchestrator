import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const scenarios = [
  {
    name: "overview",
    path: "/",
    heading: "Account protection at a glance",
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
