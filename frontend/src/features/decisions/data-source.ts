import type {
  DecisionInvestigationDetail,
  DecisionReplayComparison,
  DecisionSearchCriteria,
  DecisionSearchPage,
  DecisionSummary,
  OperationsMetric,
} from "./types";

export interface DecisionsDataSource {
  search(criteria: DecisionSearchCriteria): Promise<DecisionSearchPage>;
  investigate(decisionReference: string): Promise<DecisionInvestigationDetail>;
  replay(decisionReference: string): Promise<DecisionReplayComparison>;
  listRecent(): Promise<readonly DecisionSummary[]>;
  listOverviewMetrics(): Promise<readonly OperationsMetric[]>;
}
