import type {
  RecoveryInvestigationDetail,
  RecoverySearchCriteria,
  RecoverySearchPage,
} from "./types";

export interface RecoveriesDataSource {
  search(criteria: RecoverySearchCriteria): Promise<RecoverySearchPage>;
  investigate(recoveryReference: string): Promise<RecoveryInvestigationDetail>;
}
