import { expect, test } from "@playwright/test";

const RAW_SHOWCASE_IDENTIFIER = "acct_72c4b69e18f74291";

function durationInMilliseconds(value: string): number {
  const firstDuration = value.split(",")[0]?.trim() ?? "0s";
  if (firstDuration.endsWith("ms")) {
    return Number.parseFloat(firstDuration);
  }
  if (firstDuration.endsWith("s")) {
    return Number.parseFloat(firstDuration) * 1000;
  }
  return Number.parseFloat(firstDuration);
}

test("design-system showcase keeps masked values out of rendered output", async ({
  request,
}) => {
  const response = await request.get("/design-system");
  expect(response.ok()).toBe(true);

  const html = await response.text();
  expect(html).toContain("acct_72••••4291");
  expect(html).not.toContain(RAW_SHOWCASE_IDENTIFIER);
});

test("skip navigation is keyboard visible and targets main content", async ({
  page,
}) => {
  await page.goto("/design-system");
  await page.keyboard.press("Tab");

  const skipLink = page.getByRole("link", { name: "Skip to main content" });
  await expect(skipLink).toBeFocused();
  await expect(skipLink).toBeVisible();
  await expect(skipLink).toHaveAttribute("href", "#main-content");
});

test("reduced-motion preference removes meaningful transitions", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/design-system");

  const duration = await page
    .getByRole("link", { name: "Return to overview" })
    .first()
    .evaluate((element) => getComputedStyle(element).transitionDuration);

  expect(durationInMilliseconds(duration)).toBeLessThanOrEqual(0.001);
});
