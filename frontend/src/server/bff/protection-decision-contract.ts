import "server-only";

export {
  ProtectionDecisionContractClient,
  adaptProtectionDecision,
  mapChallengeType,
  mapGeneratedProblem,
  mapProtectionOutcome,
  mapRiskBand,
} from "./protection-decision-contract-core";

export type {
  ChallengeTypeView,
  DecisionOutcomeView,
  ProtectionDecisionView,
  RiskBandView,
} from "./protection-decision-contract-core";
