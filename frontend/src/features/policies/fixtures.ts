import type { PoliciesDataSource } from "./data-source";
import type {
  PolicyDirectorySummary,
  PolicyInvestigationDetail,
  PolicyVersionSummary,
} from "./types";

/**
 * The backend read API only exposes the *currently active* rollout
 * (`PolicyRolloutService.findActiveRollout`), not rollout history. A canary that was rolled back
 * is therefore indistinguishable, through this read model, from a policy that never had one --
 * both simply show `activeRollout: null` and `impactAvailability: "NOT_APPLICABLE"`. This is a
 * known backend constraint, not a frontend omission.
 */

function version(overrides: Partial<PolicyVersionSummary> & Pick<PolicyVersionSummary, "id" | "version" | "status" | "createdAt">): PolicyVersionSummary {
  return {
    id: overrides.id,
    policyKey: overrides.policyKey ?? "",
    version: overrides.version,
    status: overrides.status,
    allowMaxScore: overrides.allowMaxScore ?? 25,
    stepUpMaxScore: overrides.stepUpMaxScore ?? 65,
    recoveryMaxScore: overrides.recoveryMaxScore ?? 89,
    createdAt: overrides.createdAt,
    activatedAt: overrides.activatedAt ?? null,
    analysis: overrides.analysis ?? null,
    governance: overrides.governance ?? null,
  };
}

const ACCOUNT_PROTECTION: PolicyInvestigationDetail = {
  policyKey: "account-protection-default",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000002",
      policyKey: "account-protection-default",
      version: "1.0.0",
      status: "ACTIVE",
      createdAt: "2026-06-01T09:00:00.000Z",
      activatedAt: "2026-06-02T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-06-01T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-06-02T08:00:00.000Z",
        approvalReason: "Initial default policy for production rollout.",
      },
    }),
    version({
      id: "00000000-0000-4000-a000-000000000001",
      policyKey: "account-protection-default",
      version: "0.9.0",
      status: "RETIRED",
      createdAt: "2026-05-01T09:00:00.000Z",
      activatedAt: "2026-05-02T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-05-01T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-05-02T08:00:00.000Z",
        approvalReason: "Superseded by 1.0.0.",
      },
    }),
  ],
  routingScope: [
    { clientId: "default-client", eventType: "LOGIN_ATTEMPT" },
    { clientId: "default-client", eventType: "SENSITIVE_ACTION" },
    { clientId: "default-client", eventType: "LOGIN_RECOVERY_ATTEMPT" },
    { clientId: "default-client", eventType: "PASSWORD_RESET_ATTEMPT" },
    { clientId: "default-client", eventType: "CREDENTIAL_CHANGE_ATTEMPT" },
    { clientId: "default-client", eventType: "DEVICE_TRUST_RESET_ATTEMPT" },
  ],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

const CREDENTIAL_CHANGE_CANARY: PolicyInvestigationDetail = {
  policyKey: "credential-change-canary",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000012",
      policyKey: "credential-change-canary",
      version: "2.0.0",
      status: "APPROVED",
      createdAt: "2026-07-20T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-07-20T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-07-21T08:00:00.000Z",
        approvalReason: "Tighter step-up threshold for credential changes.",
      },
    }),
    version({
      id: "00000000-0000-4000-a000-000000000011",
      policyKey: "credential-change-canary",
      version: "1.0.0",
      status: "ACTIVE",
      createdAt: "2026-06-01T09:00:00.000Z",
      activatedAt: "2026-06-02T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-06-01T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-06-02T08:00:00.000Z",
        approvalReason: "Initial credential-change policy.",
      },
    }),
  ],
  routingScope: [{ clientId: "default-client", eventType: "CREDENTIAL_CHANGE_ATTEMPT" }],
  activeRollout: {
    candidateVersion: "2.0.0",
    rolloutPercentage: 25,
    status: "ACTIVE",
    startedAt: "2026-07-29T09:00:00.000Z",
    startedBy: "operator-1",
    updatedAt: "2026-07-29T09:00:00.000Z",
    rolledBackAt: null,
    rolledBackBy: null,
  },
  impactAnalysis: {
    candidatePolicyVersion: "2.0.0",
    originalPolicyVersionsObserved: ["1.0.0"],
    algorithmVersionsObserved: ["risk-score-v3"],
    totalDecisions: 240,
    divergentDecisionsCount: 18,
    divergencePercentage: 7.5,
    maxDivergencePercentageThreshold: 20,
    exceedsDivergenceThreshold: false,
    transitionMatrix: {
      ALLOW: { ALLOW: 150, REQUIRE_STEP_UP: 8, START_RECOVERY: 0, TEMPORARILY_BLOCK: 0 },
      REQUIRE_STEP_UP: { ALLOW: 2, REQUIRE_STEP_UP: 60, START_RECOVERY: 0, TEMPORARILY_BLOCK: 0 },
      START_RECOVERY: { ALLOW: 0, REQUIRE_STEP_UP: 0, START_RECOVERY: 12, TEMPORARILY_BLOCK: 0 },
      TEMPORARILY_BLOCK: { ALLOW: 0, REQUIRE_STEP_UP: 0, START_RECOVERY: 0, TEMPORARILY_BLOCK: 8 },
    },
    impactByEventType: {
      CREDENTIAL_CHANGE_ATTEMPT: { segment: "CREDENTIAL_CHANGE_ATTEMPT", totalDecisions: 240, divergentDecisions: 18 },
    },
    impactByRiskBand: {
      LOW: { segment: "LOW", totalDecisions: 150, divergentDecisions: 2 },
      MEDIUM: { segment: "MEDIUM", totalDecisions: 70, divergentDecisions: 10 },
      HIGH: { segment: "HIGH", totalDecisions: 20, divergentDecisions: 6 },
    },
    divergentDecisions: [
      {
        maskedProtectionRequestReference: "••••7a01",
        redactedAccountReference: "9f2e1c7b4a6d8035",
        originalOutcome: "ALLOW",
        candidateOutcome: "REQUIRE_STEP_UP",
        riskScore: 42,
        originalReasons: [{ code: "NEW_DEVICE", contribution: 22 }],
      },
      {
        maskedProtectionRequestReference: "••••7a02",
        redactedAccountReference: "3b8d5f0a1e7c2946",
        originalOutcome: "REQUIRE_STEP_UP",
        candidateOutcome: "ALLOW",
        riskScore: 31,
        originalReasons: [{ code: "NETWORK_RISK", contribution: 18 }],
      },
    ],
  },
  impactAvailability: "AVAILABLE",
  source: "fixtures",
};

const DEVICE_TRUST_DRAFT: PolicyInvestigationDetail = {
  policyKey: "device-trust-draft",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000021",
      policyKey: "device-trust-draft",
      version: "0.1.0",
      status: "DRAFT",
      createdAt: "2026-07-28T09:00:00.000Z",
      governance: { createdBy: "policy-author", validatedBy: null, validatedAt: null, approvedBy: null, approvedAt: null, approvalReason: null },
    }),
  ],
  routingScope: [],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

const RECOVERY_PENDING_APPROVAL: PolicyInvestigationDetail = {
  policyKey: "recovery-policy-pending-approval",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000031",
      policyKey: "recovery-policy-pending-approval",
      version: "1.0.0",
      status: "VALIDATED",
      createdAt: "2026-07-27T09:00:00.000Z",
      analysis: {
        analyzerVersion: "policy-analyzer-1.0",
        diagnostics: [
          {
            code: "STEP_UP_MAX_SCORE_CLOSE_TO_ALLOW_MAX_SCORE",
            severity: "WARNING",
            path: "stepUpMaxScore",
            message: "stepUpMaxScore is within 5 points of allowMaxScore.",
          },
        ],
      },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-07-27T10:00:00.000Z",
        approvedBy: null,
        approvedAt: null,
        approvalReason: null,
      },
    }),
  ],
  routingScope: [],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

const SENSITIVE_ACTION_INVALID: PolicyInvestigationDetail = {
  policyKey: "sensitive-action-invalid",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000041",
      policyKey: "sensitive-action-invalid",
      version: "1.0.0",
      status: "VALIDATED",
      createdAt: "2026-07-26T09:00:00.000Z",
      analysis: {
        analyzerVersion: "policy-analyzer-1.0",
        diagnostics: [
          {
            code: "STEP_UP_MAX_SCORE_MISSING",
            severity: "ERROR",
            path: "stepUpMaxScore",
            message: "stepUpMaxScore is required and was not provided.",
          },
        ],
      },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-07-26T10:00:00.000Z",
        approvedBy: null,
        approvedAt: null,
        approvalReason: null,
      },
    }),
  ],
  routingScope: [],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

const STEP_UP_APPROVED: PolicyInvestigationDetail = {
  policyKey: "step-up-policy-approved",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000051",
      policyKey: "step-up-policy-approved",
      version: "1.0.0",
      status: "APPROVED",
      createdAt: "2026-07-25T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-07-25T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-07-26T08:00:00.000Z",
        approvalReason: "Ready for activation pending scheduled maintenance window.",
      },
    }),
  ],
  routingScope: [],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

const WEBHOOK_REJECTED: PolicyInvestigationDetail = {
  policyKey: "webhook-policy-rejected",
  versions: [
    version({
      id: "00000000-0000-4000-a000-000000000061",
      policyKey: "webhook-policy-rejected",
      version: "1.0.0",
      status: "REJECTED",
      createdAt: "2026-07-24T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-07-24T10:00:00.000Z",
        approvedBy: null,
        approvedAt: null,
        approvalReason: null,
      },
    }),
  ],
  routingScope: [],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

const DETAILS: readonly PolicyInvestigationDetail[] = [
  ACCOUNT_PROTECTION,
  CREDENTIAL_CHANGE_CANARY,
  DEVICE_TRUST_DRAFT,
  RECOVERY_PENDING_APPROVAL,
  SENSITIVE_ACTION_INVALID,
  STEP_UP_APPROVED,
  WEBHOOK_REJECTED,
];

function toSummary(detail: PolicyInvestigationDetail): PolicyDirectorySummary {
  const active = detail.versions.find((item) => item.status === "ACTIVE") ?? null;
  return {
    policyKey: detail.policyKey,
    totalVersions: detail.versions.length,
    activeVersion: active?.version ?? null,
    activeVersionActivatedAt: active?.activatedAt ?? null,
    hasActiveRollout: detail.activeRollout !== null,
  };
}

export const fixturePoliciesDataSource: PoliciesDataSource = {
  async search() {
    return {
      policies: DETAILS.map(toSummary),
      source: "fixtures",
    };
  },
  async investigate(policyKey) {
    const detail = DETAILS.find((item) => item.policyKey === policyKey);
    if (!detail) throw new Error("Policy fixture was not found.");
    return detail;
  },
};
