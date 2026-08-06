import { expect, test } from "@playwright/test";

const SESSION_COOKIE_NAME = "as_session";

async function login(page: import("@playwright/test").Page, username = "operator-1", password = "accountshield-demo-operator") {
  await page.goto("/login");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("/");
}

test("an operator can sign in and sign out through the real UI", async ({ page }) => {
  await login(page);

  await expect(page.getByText("Signed in as operator-1", { exact: false }).first()).toBeVisible();

  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(/\/login$/);
  await expect(page.getByRole("heading", { level: 1, name: "Operator sign in" })).toBeVisible();
});

test("an unknown username and a wrong password fail identically, with no enumeration hint", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Username").fill("operator-1");
  await page.getByLabel("Password").fill("not-the-real-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Invalid username or password.")).toBeVisible();

  await page.getByLabel("Username").fill("no-such-operator");
  await page.getByLabel("Password").fill("whatever");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Invalid username or password.")).toBeVisible();
});

test("the session cookie rotates across the login boundary and is cleared on logout", async ({ page, context }) => {
  const beforeLogin = (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE_NAME);
  expect(beforeLogin).toBeUndefined();

  await login(page);
  const afterLogin = (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE_NAME);
  expect(afterLogin?.value).toBeTruthy();

  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(/\/login$/);
  const afterLogout = (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE_NAME);
  expect(afterLogout).toBeUndefined();
});

test("a cookie value captured before logout is rejected after server-side revocation (no replay)", async ({
  page,
  context,
  request,
}) => {
  await login(page);
  const sessionCookie = (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE_NAME);
  expect(sessionCookie?.value).toBeTruthy();

  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(/\/login$/);

  const replay = await request.get("/api/bff/session/status", {
    headers: { cookie: `${SESSION_COOKIE_NAME}=${sessionCookie?.value}` },
  });
  const body = (await replay.json()) as { authenticated: boolean };
  expect(body.authenticated).toBe(false);
});

test("a mutating request without the CSRF header is rejected even with a valid session cookie", async ({ page }) => {
  await login(page);

  // page.request (not the standalone `request` fixture) shares the browser context's cookie
  // jar, so this genuinely carries the valid session cookie while omitting the CSRF header.
  const response = await page.request.post("/api/bff/session/logout");
  expect(response.status()).toBe(403);

  // The UI-driven logout (which does send the CSRF header) must still work afterward.
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(/\/login$/);
});

test("logging out in one tab signs the other tab out via the multi-tab broadcast", async ({ context }) => {
  const pageOne = await context.newPage();
  await login(pageOne);

  const pageTwo = await context.newPage();
  await pageTwo.goto("/");
  await expect(pageTwo.getByText("Signed in as operator-1", { exact: false }).first()).toBeVisible();

  await pageOne.getByRole("button", { name: "Sign out" }).click();
  await pageOne.waitForURL(/\/login$/);

  // /login itself has no session badge to assert on -- the broadcast-triggered navigation away
  // from the (now stale) authenticated page is the observable multi-tab effect.
  await pageTwo.waitForURL(/\/login$/, { timeout: 10_000 });
  await expect(pageTwo.getByRole("heading", { level: 1, name: "Operator sign in" })).toBeVisible();
});

test("the backend token never appears in HTML, storage, or network response bodies", async ({ page }) => {
  const responseBodies: string[] = [];
  page.on("response", async (response) => {
    if (response.url().includes("/api/bff/session/")) {
      try {
        responseBodies.push(await response.text());
      } catch {
        // Response body already consumed or unavailable -- not relevant to this leak check.
      }
    }
  });

  await login(page);

  for (const body of responseBodies) {
    expect(body).not.toContain(".fixture");
  }

  const html = await page.content();
  expect(html).not.toContain(".fixture");

  const storageDump = await page.evaluate(() => ({
    local: JSON.stringify(localStorage),
    session: JSON.stringify(sessionStorage),
  }));
  expect(storageDump.local).not.toContain(".fixture");
  expect(storageDump.session).not.toContain(".fixture");
});
