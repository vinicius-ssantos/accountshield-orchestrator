import { describe, expect, it } from "vitest";

import { fixturePolicyRolloutService } from "./policy-rollout-fixtures";

const KEY = "credential-change-canary";
const VERSION = "2.0.0";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe("fixturePolicyRolloutService", () => {
  it("issues a UUID-shaped challengeId and contextId for starting a rollout", async () => {
    const step = await fixturePolicyRolloutService.requestStartStepUp(
      { policyKey: KEY, candidateVersion: VERSION },
      "corr-1",
    );
    expect(step.challengeId).toMatch(UUID_PATTERN);
    expect(step.contextId).toMatch(UUID_PATTERN);
    expect(step.simulatedCode).toBe("731045");
  });

  it("starts a rollout at the requested candidate version and percentage", async () => {
    const summary = await fixturePolicyRolloutService.startRollout(
      { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 30, stepUpChallengeId: "33333333-3333-4333-9333-000000000000" },
      "corr-1",
    );
    expect(summary).toMatchObject({ policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 30, status: "ACTIVE" });
  });

  it("issues a UUID-shaped challengeId and contextId for a percentage update", async () => {
    const step = await fixturePolicyRolloutService.requestPercentageStepUp({ policyKey: KEY }, "corr-1");
    expect(step.challengeId).toMatch(UUID_PATTERN);
    expect(step.contextId).toMatch(UUID_PATTERN);
    expect(step.simulatedCode).toBe("731045");
  });

  it("updates the rollout percentage", async () => {
    const summary = await fixturePolicyRolloutService.updatePercentage(
      { policyKey: KEY, rolloutPercentage: 75, stepUpChallengeId: "33333333-3333-4333-9333-000000000000" },
      "corr-1",
    );
    expect(summary).toMatchObject({ policyKey: KEY, rolloutPercentage: 75, status: "ACTIVE" });
  });

  it("rolls back immediately with no step-up challenge required by the interface", async () => {
    const summary = await fixturePolicyRolloutService.rollback({ policyKey: KEY }, "corr-1");
    expect(summary).toMatchObject({ policyKey: KEY, status: "ROLLED_BACK", rolloutPercentage: 0 });
    expect(summary.rolledBackAt).not.toBeNull();
    expect(summary.rolledBackBy).not.toBeNull();
  });
});
