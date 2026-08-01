import { describe, expect, it } from "vitest";

import { fixturePolicyLifecycleService } from "./policy-lifecycle-fixtures";

const KEY = "account-protection-default";
const VERSION = "2.0.0";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe("fixturePolicyLifecycleService", () => {
  it("issues a UUID-shaped challengeId and contextId that would pass the real request parser", async () => {
    const step = await fixturePolicyLifecycleService.requestStepUp(
      "APPROVE",
      { policyKey: KEY, version: VERSION },
      "corr-1",
    );
    expect(step.challengeId).toMatch(UUID_PATTERN);
    expect(step.contextId).toMatch(UUID_PATTERN);
    expect(step.simulatedCode).toBe("731045");
  });

  it("verifies the disclosed code end to end, matching what requestStepUp issued", async () => {
    const step = await fixturePolicyLifecycleService.requestStepUp(
      "ACTIVATE",
      { policyKey: KEY, version: VERSION },
      "corr-1",
    );
    const verification = await fixturePolicyLifecycleService.verifyStepUp(
      { challengeId: step.challengeId, contextId: step.contextId, providedCode: step.simulatedCode! },
      "corr-1",
    );
    expect(verification.verified).toBe(true);
  });

  it("rejects an incorrect code", async () => {
    const verification = await fixturePolicyLifecycleService.verifyStepUp(
      { challengeId: "33333333-3333-4333-9333-000000000000", contextId: "44444444-4444-4444-9444-000000000000", providedCode: "000000" },
      "corr-1",
    );
    expect(verification.verified).toBe(false);
  });

  it("submits approve, activate, reject, and retire with the corresponding status", async () => {
    await expect(
      fixturePolicyLifecycleService.approve(
        { policyKey: KEY, version: VERSION, stepUpChallengeId: "33333333-3333-4333-9333-000000000000", reason: "demo" },
        "corr-1",
      ),
    ).resolves.toEqual({ status: "APPROVED" });
    await expect(
      fixturePolicyLifecycleService.activate(
        { policyKey: KEY, version: VERSION, stepUpChallengeId: "33333333-3333-4333-9333-000000000000" },
        "corr-1",
      ),
    ).resolves.toEqual({ status: "ACTIVE" });
    await expect(
      fixturePolicyLifecycleService.reject({ policyKey: KEY, version: VERSION }, "corr-1"),
    ).resolves.toEqual({ status: "REJECTED" });
    await expect(
      fixturePolicyLifecycleService.retire(
        { policyKey: KEY, version: VERSION, stepUpChallengeId: "33333333-3333-4333-9333-000000000000" },
        "corr-1",
      ),
    ).resolves.toEqual({ status: "RETIRED" });
  });
});
