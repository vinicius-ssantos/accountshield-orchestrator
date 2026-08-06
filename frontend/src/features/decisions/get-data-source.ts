import type { DecisionsDataSource } from "./data-source";
import { fixtureDecisionsDataSource } from "./fixtures";

export function getDecisionsDataSource(): DecisionsDataSource {
  const configuredSource =
    process.env.ACCOUNTSHIELD_DATA_SOURCE ?? (process.env.NODE_ENV === "development" ? "fixtures" : undefined);

  if (configuredSource === "fixtures") {
    return fixtureDecisionsDataSource;
  }

  if (!configuredSource) {
    throw new Error("ACCOUNTSHIELD_DATA_SOURCE must be configured outside local development.");
  }

  throw new Error(`Unsupported AccountShield data source: ${configuredSource}`);
}
