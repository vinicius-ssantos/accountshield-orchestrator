import type { PolicyDirectoryPage, PolicyInvestigationDetail } from "./types";

export interface PoliciesDataSource {
  search(): Promise<PolicyDirectoryPage>;
  investigate(policyKey: string): Promise<PolicyInvestigationDetail>;
}
