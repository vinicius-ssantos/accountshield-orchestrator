import type { DecisionSummary, OperationsMetric } from "./types";

export interface DecisionsDataSource {
  listRecent(): Promise<readonly DecisionSummary[]>;
  listOverviewMetrics(): Promise<readonly OperationsMetric[]>;
}
