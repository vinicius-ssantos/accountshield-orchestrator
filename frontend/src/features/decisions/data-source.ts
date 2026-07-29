import type {
  DecisionSearchCriteria,
  DecisionSearchPage,
  DecisionSummary,
  OperationsMetric,
} from "./types";

export interface DecisionsDataSource {
  search(criteria: DecisionSearchCriteria): Promise<DecisionSearchPage>;
  listRecent(): Promise<readonly DecisionSummary[]>;
  listOverviewMetrics(): Promise<readonly OperationsMetric[]>;
}
