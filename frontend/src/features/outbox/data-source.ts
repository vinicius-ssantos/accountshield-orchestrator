import type { OutboxSearchFilters, OutboxSearchResult } from "./types";

export interface OutboxDataSource {
  search(filters: OutboxSearchFilters): Promise<OutboxSearchResult>;
}
