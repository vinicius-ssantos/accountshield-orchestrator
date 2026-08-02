import type { PolicyRolloutService } from "./policy-rollout-core";

// Deterministic, permissive demo service: fixtures mode never calls the backend, so it never
// enforces the real one-time-consumption/expiry/candidate-must-be-approved semantics the live
// client does. It exists only to make the rollout controls demoable end to end without a running
// backend, mirroring policy-lifecycle-fixtures.ts.
//
// Must match policy-lifecycle-fixtures.ts's FIXTURE_SIMULATED_CODE exactly: rollout step-up
// verification reuses policy lifecycle's /api/bff/policy-lifecycle/verify route (see ADR 0019)
// rather than a duplicate, and in fixtures mode that route is backed by
// fixturePolicyLifecycleService.verifyStepUp, which only accepts this one hardcoded value
// regardless of which mutation module issued the challenge.
const FIXTURE_SIMULATED_CODE = "731045";

// The BFF validates challengeId/contextId against the same UUID-shaped pattern the real backend
// uses, so fixture identifiers must be UUID-shaped too, or every verify/submit call is rejected
// as a malformed request before it ever reaches this service's own logic (issue #196 caught this
// the hard way for recovery review; every mutation module since has mirrored the fix).
function fixtureId(prefix: string, seed: string): string {
  const tail = seed
    .split("")
    .reduce((hash, char) => ((hash << 5) - hash + char.charCodeAt(0)) | 0, 0)
    .toString(16)
    .replace("-", "")
    .padStart(12, "0")
    .slice(-12);
  return `${prefix}-3333-4333-9333-${tail}`;
}

export const fixturePolicyRolloutService: PolicyRolloutService = {
  async requestStartStepUp(input) {
    const seed = `START_ROLLOUT:${input.policyKey}:${input.candidateVersion}`;
    return {
      challengeId: fixtureId("55555555", seed),
      simulatedCode: FIXTURE_SIMULATED_CODE,
      contextId: fixtureId("66666666", seed),
    };
  },
  async startRollout(input) {
    const now = new Date().toISOString();
    return {
      policyKey: input.policyKey,
      candidateVersion: input.candidateVersion,
      rolloutPercentage: input.rolloutPercentage,
      status: "ACTIVE",
      startedAt: now,
      startedBy: "operator-1",
      updatedAt: now,
      rolledBackAt: null,
      rolledBackBy: null,
    };
  },
  async requestPercentageStepUp(input) {
    const seed = `UPDATE_ROLLOUT:${input.policyKey}`;
    return {
      challengeId: fixtureId("77777777", seed),
      simulatedCode: FIXTURE_SIMULATED_CODE,
      contextId: fixtureId("88888888", seed),
    };
  },
  async updatePercentage(input) {
    const now = new Date().toISOString();
    return {
      policyKey: input.policyKey,
      candidateVersion: "2.0.0",
      rolloutPercentage: input.rolloutPercentage,
      status: "ACTIVE",
      startedAt: now,
      startedBy: "operator-1",
      updatedAt: now,
      rolledBackAt: null,
      rolledBackBy: null,
    };
  },
  async rollback(input) {
    const now = new Date().toISOString();
    return {
      policyKey: input.policyKey,
      candidateVersion: "2.0.0",
      rolloutPercentage: 0,
      status: "ROLLED_BACK",
      startedAt: now,
      startedBy: "operator-1",
      updatedAt: now,
      rolledBackAt: now,
      rolledBackBy: "operator-1",
    };
  },
};
