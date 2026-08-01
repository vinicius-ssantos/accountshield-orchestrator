import type { PolicyLifecycleService } from "./policy-lifecycle-core";

// Deterministic, permissive demo service: fixtures mode never calls the backend, so it never
// enforces the real one-time-consumption/expiry/self-approval semantics the live client does. It
// exists only to make the lifecycle actions demoable end to end without a running backend.
const FIXTURE_SIMULATED_CODE = "731045";

// The BFF validates challengeId/contextId against the same UUID-shaped pattern the real backend
// uses, so fixture identifiers must be UUID-shaped too, or every verify/submit call is rejected
// as a malformed request before it ever reaches this service's own logic (issue #196 caught this
// the hard way for recovery review; policy lifecycle mirrors that fix from the start).
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

export const fixturePolicyLifecycleService: PolicyLifecycleService = {
  async requestStepUp(action, ref) {
    const seed = `${action}:${ref.policyKey}:${ref.version}`;
    return {
      challengeId: fixtureId("33333333", seed),
      simulatedCode: FIXTURE_SIMULATED_CODE,
      contextId: fixtureId("44444444", seed),
    };
  },
  async verifyStepUp(input) {
    const verified = input.providedCode === FIXTURE_SIMULATED_CODE;
    return { verified, status: verified ? "VERIFIED" : "CHALLENGED", remainingAttempts: verified ? 3 : 2 };
  },
  async approve() {
    return { status: "APPROVED" };
  },
  async activate() {
    return { status: "ACTIVE" };
  },
  async reject() {
    return { status: "REJECTED" };
  },
  async retire() {
    return { status: "RETIRED" };
  },
};
