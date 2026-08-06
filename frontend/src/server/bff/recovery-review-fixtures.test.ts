import { describe, expect, it } from "vitest";

import { fixtureRecoveryReviewService } from "./recovery-review-fixtures";

const REFERENCE = "00000000-0000-4000-9000-000000000003";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe("fixtureRecoveryReviewService", () => {
  it("issues a UUID-shaped challenge id that would pass the real request parser", async () => {
    const step = await fixtureRecoveryReviewService.requestStepUp(
      { recoveryReference: REFERENCE },
      "corr-1",
    );
    expect(step.challengeId).toMatch(UUID_PATTERN);
    expect(step.simulatedCode).toBe("482913");
  });

  it("verifies the disclosed code end to end, matching what requestStepUp issued", async () => {
    const step = await fixtureRecoveryReviewService.requestStepUp(
      { recoveryReference: REFERENCE },
      "corr-1",
    );
    const verification = await fixtureRecoveryReviewService.verifyStepUp(
      { recoveryReference: REFERENCE, challengeId: step.challengeId, providedCode: step.simulatedCode! },
      "corr-1",
    );
    expect(verification.verified).toBe(true);
  });

  it("rejects an incorrect code", async () => {
    const verification = await fixtureRecoveryReviewService.verifyStepUp(
      { recoveryReference: REFERENCE, challengeId: "22222222-2222-4222-9222-000000000003", providedCode: "000000" },
      "corr-1",
    );
    expect(verification.verified).toBe(false);
  });

  it("submits approve and reject decisions with the corresponding recovery status", async () => {
    await expect(
      fixtureRecoveryReviewService.submitReview(
        { recoveryReference: REFERENCE, decision: "APPROVE", stepUpChallengeId: "22222222-2222-4222-9222-000000000003" },
        "corr-1",
      ),
    ).resolves.toEqual({ status: "COMPLETED" });
    await expect(
      fixtureRecoveryReviewService.submitReview(
        { recoveryReference: REFERENCE, decision: "REJECT", stepUpChallengeId: "22222222-2222-4222-9222-000000000003" },
        "corr-1",
      ),
    ).resolves.toEqual({ status: "REJECTED" });
  });
});
