import {
  createProtectionDecision,
  type AccountShieldGeneratedTransport,
} from "@/generated/accountshield/openapi-client";
import {
  ChallengeTypeKnownValues,
  ProblemCodeKnownValues,
  ProtectionOutcomeKnownValues,
  RiskBandKnownValues,
  type ChallengeType,
  type ProblemCode,
  type ProblemDetails,
  type ProtectionDecisionRequest,
  type ProtectionDecisionResponse,
  type ProtectionOutcome,
  type RiskBand,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

export type DecisionOutcomeView =
  | "allow"
  | "step-up"
  | "recovery"
  | "blocked"
  | "unknown";
export type RiskBandView = "low" | "medium" | "high" | "unknown";
export type ChallengeTypeView = "totp" | "email" | "webauthn" | "unknown";

export interface ProtectionDecisionView {
  readonly decisionId: string;
  readonly outcome: DecisionOutcomeView;
  readonly riskScore: number;
  readonly riskBand: RiskBandView;
  readonly algorithmVersion: string;
  readonly policy: {
    readonly key: string;
    readonly version: string;
  };
  readonly reasons: ReadonlyArray<{
    readonly code: string;
    readonly contribution: number;
  }>;
  readonly decidedAt: string;
  readonly challenge: null | {
    readonly type: ChallengeTypeView;
    readonly expiresAt: string;
  };
}

function includesKnown<T extends string>(
  values: readonly T[],
  value: string,
): value is T {
  return (values as readonly string[]).includes(value);
}

export function mapProtectionOutcome(
  value: ProtectionOutcome,
): DecisionOutcomeView {
  if (!includesKnown(ProtectionOutcomeKnownValues, value)) return "unknown";

  switch (value) {
    case "ALLOW":
      return "allow";
    case "REQUIRE_STEP_UP":
      return "step-up";
    case "START_RECOVERY":
      return "recovery";
    case "TEMPORARILY_BLOCK":
      return "blocked";
  }
}

export function mapRiskBand(value: RiskBand): RiskBandView {
  if (!includesKnown(RiskBandKnownValues, value)) return "unknown";

  switch (value) {
    case "LOW":
      return "low";
    case "MEDIUM":
      return "medium";
    case "HIGH":
      return "high";
  }
}

export function mapChallengeType(value: ChallengeType): ChallengeTypeView {
  if (!includesKnown(ChallengeTypeKnownValues, value)) return "unknown";

  switch (value) {
    case "TOTP_SIMULATED":
      return "totp";
    case "EMAIL_SIMULATED":
      return "email";
    case "WEBAUTHN_SIMULATED":
      return "webauthn";
  }
}

export function adaptProtectionDecision(
  response: ProtectionDecisionResponse,
): ProtectionDecisionView {
  return {
    decisionId: response.decisionId,
    outcome: mapProtectionOutcome(response.outcome),
    riskScore: response.riskScore,
    riskBand: mapRiskBand(response.riskBand),
    algorithmVersion: response.algorithmVersion,
    policy: {
      key: response.policyKey,
      version: response.policyVersion,
    },
    reasons: response.reasons.map((reason) => ({
      code: reason.code,
      contribution: reason.contribution,
    })),
    decidedAt: response.decidedAt,
    challenge: response.challenge
      ? {
          type: mapChallengeType(response.challenge.challengeType),
          expiresAt: response.challenge.expiresAt,
        }
      : null,
  };
}

function knownProblemCode(value: ProblemCode | undefined): value is ProblemCode {
  return Boolean(
    value && includesKnown(ProblemCodeKnownValues, value),
  );
}

export function mapGeneratedProblem(problem: ProblemDetails): BffError {
  if (knownProblemCode(problem.code)) {
    switch (problem.code) {
      case "INVALID_PROTECTION_REQUEST":
        return new BffError(
          "INVALID_REQUEST",
          400,
          "The protection request is invalid.",
        );
      case "ACTIVE_POLICY_UNAVAILABLE":
        return new BffError(
          "UPSTREAM_UNAVAILABLE",
          503,
          "The active protection policy is unavailable.",
          true,
        );
      case "IDEMPOTENCY_CONFLICT":
        return new BffError(
          "CONFLICT",
          409,
          "The request conflicts with an existing operation.",
        );
      case "RATE_LIMIT_EXCEEDED":
        return new BffError(
          "RATE_LIMITED",
          429,
          "Too many requests were submitted.",
          true,
        );
    }
  }

  switch (problem.status) {
    case 401:
      return new BffError("UNAUTHORIZED", 401, "Authentication is required.");
    case 403:
      return new BffError(
        "FORBIDDEN",
        403,
        "The operation is not permitted.",
      );
    case 409:
      return new BffError(
        "CONFLICT",
        409,
        "The request conflicts with an existing operation.",
      );
    case 429:
      return new BffError(
        "RATE_LIMITED",
        429,
        "Too many requests were submitted.",
        true,
      );
    default:
      return new BffError(
        "UPSTREAM_UNAVAILABLE",
        problem.status >= 500 ? 503 : 502,
        "The AccountShield service could not complete the request.",
        problem.status >= 500,
      );
  }
}

export class ProtectionDecisionContractClient {
  constructor(private readonly transport: AccountShieldGeneratedTransport) {}

  async create(
    request: ProtectionDecisionRequest,
    signal?: AbortSignal,
  ): Promise<ProtectionDecisionView> {
    const response = await createProtectionDecision(
      this.transport,
      request,
      signal,
    );
    return adaptProtectionDecision(response);
  }
}
