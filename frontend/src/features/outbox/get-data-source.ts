import type { OutboxDataSource } from "./data-source";
import { fixtureOutboxDataSource } from "./fixtures";

export function getOutboxDataSource(): OutboxDataSource {
  const configuredSource =
    process.env.ACCOUNTSHIELD_DATA_SOURCE ?? (process.env.NODE_ENV === "development" ? "fixtures" : undefined);

  if (configuredSource === "fixtures") {
    return fixtureOutboxDataSource;
  }

  if (!configuredSource) {
    throw new Error("ACCOUNTSHIELD_DATA_SOURCE must be configured outside local development.");
  }

  throw new Error(`Unsupported AccountShield data source: ${configuredSource}`);
}
