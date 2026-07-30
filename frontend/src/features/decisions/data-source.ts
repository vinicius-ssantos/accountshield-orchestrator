import type {
  DecisionInvestigationDetail,
  DecisionSearchCriteria,
  DecisionSearchPage,
  DecisionSummary,
  OperationsMetric,
} from "./types";

export interface DecisionsDataSource {
  search(criteria: DecisionSearchCriteria): Promise<DecisionSearchPage>;
  investigate(decisionReference: string): Promise<DecisionInvestigationDetail>;
  listRecent(): Promise<readonly DecisionSummary[]>;
  listOverviewMetrics(): Promise<readonly OperationsMetric[]>;
}
