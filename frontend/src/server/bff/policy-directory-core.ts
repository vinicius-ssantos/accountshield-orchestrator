import {
  searchPolicyDirectory,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type { PolicyDirectorySearchResponse } from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

export interface PolicyDirectorySummary {
  policyKey: string;
  totalVersions: number;
  activeVersion: string | null;
  activeVersionActivatedAt: string | null;
  hasActiveRollout: boolean;
}

export interface PolicyDirectoryResult {
  policies: readonly PolicyDirectorySummary[];
  source: "fixtures" | "live";
}

export interface PolicyDirectoryService {
  search(correlationId: string, signal?: AbortSignal): Promise<PolicyDirectoryResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

function requiredString(record: Record<string, unknown>, key: string, maximum = 100): string {
  const value = record[key];
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value;
}

function nullableString(record: Record<string, unknown>, key: string, maximum = 100): string | null {
  const value = record[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value;
}

function requiredBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") malformed();
  return value;
}

function requiredInteger(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (!Number.isInteger(value) || (value as number) < 0) malformed();
  return value as number;
}

function parseSummary(value: unknown): PolicyDirectorySummary {
  if (!isRecord(value)) malformed();
  return {
    policyKey: requiredString(value, "policyKey"),
    totalVersions: requiredInteger(value, "totalVersions"),
    activeVersion: nullableString(value, "activeVersion", 40),
    activeVersionActivatedAt: nullableString(value, "activeVersionActivatedAt", 40),
    hasActiveRollout: requiredBoolean(value, "hasActiveRollout"),
  };
}

export function parsePolicyDirectoryResponse(value: unknown): PolicyDirectorySearchResponse {
  if (!isRecord(value) || !Array.isArray(value.policies)) malformed();
  const policies = value.policies.map(parseSummary);
  return { policies } as PolicyDirectorySearchResponse;
}

export class AccountShieldPolicyDirectoryClient implements PolicyDirectoryService {
  constructor(
    private readonly configuration: {
      origin: string;
      operatorToken: string;
      timeoutMs: number;
      maxResponseBytes: number;
      fetchImplementation?: typeof fetch;
    },
  ) {}

  async search(correlationId: string, signal?: AbortSignal): Promise<PolicyDirectoryResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response = await searchPolicyDirectory(transport, {}, signal);
    const parsed = parsePolicyDirectoryResponse(response);
    return {
      policies: parsed.policies.map((summary) => ({
        policyKey: summary.policyKey,
        totalVersions: summary.totalVersions,
        activeVersion: summary.activeVersion ?? null,
        activeVersionActivatedAt: summary.activeVersionActivatedAt ?? null,
        hasActiveRollout: summary.hasActiveRollout,
      })),
      source: "live",
    };
  }

  private async execute<TResponse>(
    request: GeneratedTransportRequest,
    correlationId: string,
  ): Promise<TResponse> {
    const fetchImplementation = this.configuration.fetchImplementation ?? fetch;
    const timeoutSignal = AbortSignal.timeout(this.configuration.timeoutMs);
    const requestSignal = request.signal
      ? AbortSignal.any([request.signal, timeoutSignal])
      : timeoutSignal;

    let response: Response;
    try {
      response = await fetchImplementation(new URL(request.path, this.configuration.origin), {
        method: request.method,
        headers: {
          accept: "application/json",
          authorization: `Bearer ${this.configuration.operatorToken}`,
          "content-type": "application/json",
          "x-correlation-id": correlationId,
        },
        body: request.body === undefined ? undefined : JSON.stringify(request.body),
        cache: "no-store",
        signal: requestSignal,
      });
    } catch (error) {
      if (requestSignal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The policy service timed out.", true, {
          cause: error,
        });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The policy service is unavailable.", true, {
        cause: error,
      });
    }

    if (response.status === 401) {
      throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
    }
    if (response.status === 403) {
      throw new BffError("FORBIDDEN", 403, "Operator access is not permitted.");
    }
    if (response.status === 429) {
      throw new BffError("RATE_LIMITED", 429, "The policy service is temporarily rate limited.", true);
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The policy service is unavailable.", true);
    }

    const declaredLength = Number.parseInt(response.headers.get("content-length") ?? "", 10);
    if (Number.isFinite(declaredLength) && declaredLength > this.configuration.maxResponseBytes) {
      malformed();
    }
    const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
    if (contentType !== "application/json") malformed();

    const body = new Uint8Array(await response.arrayBuffer());
    if (body.byteLength > this.configuration.maxResponseBytes) malformed();
    try {
      return JSON.parse(new TextDecoder().decode(body)) as TResponse;
    } catch (error) {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
        cause: error,
      });
    }
  }
}
