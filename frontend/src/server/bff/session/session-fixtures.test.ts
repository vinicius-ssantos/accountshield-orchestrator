import { describe, expect, it } from "vitest";

import { BffError } from "../foundation";
import { fixtureSessionTokenService } from "./session-fixtures";

describe("fixtureSessionTokenService", () => {
  it("issues a token for a correct published demo credential", async () => {
    const service = fixtureSessionTokenService();
    const token = await service.issueToken(
      { username: "operator-1", password: "accountshield-demo-operator" },
      "corr",
    );

    const payload = JSON.parse(Buffer.from(token.token.split(".")[1] ?? "", "base64url").toString("utf8"));
    expect(payload).toEqual({ sub: "operator-1", roles: ["SECURITY_OPERATOR"] });
    expect(new Date(token.expiresAt).getTime()).toBeGreaterThan(Date.now());
  });

  it("rejects a wrong password with the same error as an unknown username", async () => {
    const service = fixtureSessionTokenService();

    await expect(
      service.issueToken({ username: "operator-1", password: "wrong" }, "corr"),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(
      service.issueToken({ username: "no-such-user", password: "whatever" }, "corr"),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("refreshes a fixture token, keeping the same subject and roles", async () => {
    const service = fixtureSessionTokenService();
    const issued = await service.issueToken({ username: "admin-1", password: "accountshield-demo-admin" }, "corr");

    const refreshed = await service.refreshToken(issued.token, "corr");

    const payload = JSON.parse(Buffer.from(refreshed.token.split(".")[1] ?? "", "base64url").toString("utf8"));
    expect(payload).toEqual({ sub: "admin-1", roles: ["POLICY_ADMIN"] });
  });

  it("rejects a malformed refresh token", async () => {
    const service = fixtureSessionTokenService();

    await expect(service.refreshToken("not-a-token", "corr")).rejects.toBeInstanceOf(BffError);
  });
});
