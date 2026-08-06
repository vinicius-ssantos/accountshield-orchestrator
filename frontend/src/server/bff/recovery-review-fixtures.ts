import type { RecoveryReviewService } from "./recovery-review-core";

// Deterministic, permissive demo service: fixtures mode never calls the backend, so it never
// enforces the real one-time-consumption/expiry semantics the live client does. It exists only
// to make the review flow demoable end to end without a running backend.
const FIXTURE_SIMULATED_CODE = "482913";

// The BFF validates challengeId against the same UUID-shaped pattern the real backend uses, so a
// fixture challenge id must be UUID-shaped too, or every verify/submit call is rejected as a
// malformed request before it ever reaches this service's own logic.
function fixtureChallengeId(recoveryReference: string): string {
  const tail = recoveryReference.split("-").pop() ?? "000000000000";
  return `22222222-2222-4222-9222-${tail}`;
}

export const fixtureRecoveryReviewService: RecoveryReviewService = {
  async requestStepUp(input) {
    return { challengeId: fixtureChallengeId(input.recoveryReference), simulatedCode: FIXTURE_SIMULATED_CODE };
  },
  async verifyStepUp(input) {
    const verified = input.providedCode === FIXTURE_SIMULATED_CODE;
    return { verified, status: verified ? "VERIFIED" : "CHALLENGED", remainingAttempts: verified ? 3 : 2 };
  },
  async submitReview(input) {
    return { status: input.decision === "APPROVE" ? "COMPLETED" : "REJECTED" };
  },
};
