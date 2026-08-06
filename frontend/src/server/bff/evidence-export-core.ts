import { BffError } from "./foundation";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REASON_MAX = 500;

export interface EvidenceExportInput {
  protectionRequestId: string;
  reason: string;
}

export interface EvidenceManifest {
  bundleSchemaVersion: string;
  decisionId: string;
  protectionRequestId: string;
  generatedAt: string;
  exportedBy: string;
  exportReason: string;
  contentHashAlgorithm: string;
  contentHash: string;
  signatureAlgorithm: string;
  signature: string;
  signingPublicKey: string;
}

export interface EvidenceBundle {
  manifest: EvidenceManifest;
  content: Record<string, unknown>;
}

export interface EvidenceExportService {
  export(
    input: EvidenceExportInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<EvidenceBundle>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

function requiredString(record: Record<string, unknown>, key: string, maximum: number): string {
  const value = record[key];
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value;
}

export function parseEvidenceExportInput(body: Record<string, unknown>): EvidenceExportInput {
  const protectionRequestId = body.protectionRequestId;
  if (typeof protectionRequestId !== "string" || !UUID_PATTERN.test(protectionRequestId)) {
    throw new BffError("INVALID_REQUEST", 400, "protectionRequestId must be a valid identifier.");
  }
  const reason = body.reason;
  if (typeof reason !== "string" || reason.trim().length === 0 || reason.length > REASON_MAX) {
    throw new BffError("INVALID_REQUEST", 400, "reason must contain between 1 and 500 characters.");
  }
  return { protectionRequestId: protectionRequestId.toLowerCase(), reason };
}

export function parseEvidenceManifest(value: unknown): EvidenceManifest {
  if (!isRecord(value)) malformed();
  return {
    bundleSchemaVersion: requiredString(value, "bundleSchemaVersion", 64),
    decisionId: requiredString(value, "decisionId", 64),
    protectionRequestId: requiredString(value, "protectionRequestId", 64),
    generatedAt: requiredString(value, "generatedAt", 64),
    exportedBy: requiredString(value, "exportedBy", 128),
    exportReason: requiredString(value, "exportReason", REASON_MAX),
    contentHashAlgorithm: requiredString(value, "contentHashAlgorithm", 32),
    contentHash: requiredString(value, "contentHash", 128),
    signatureAlgorithm: requiredString(value, "signatureAlgorithm", 32),
    signature: requiredString(value, "signature", 4096),
    signingPublicKey: requiredString(value, "signingPublicKey", 8192),
  };
}

export function parseEvidenceBundle(value: unknown): EvidenceBundle {
  if (!isRecord(value)) malformed();
  const manifest = parseEvidenceManifest(value.manifest);
  if (!isRecord(value.content)) malformed();
  return { manifest, content: value.content };
}

// Evidence export has only one upstream-specific vocabulary word (EVIDENCE_INVALID_REQUEST, from
// EvidenceProblemHandler's IllegalArgumentException mapping) which folds into the generic
// INVALID_REQUEST code -- unlike the prior mutations, there is no distinct conflict state to give
// its own BffErrorCode, since export either finds the protection request or it doesn't (404).
function mapUpstreamFailure(status: number, body: Record<string, unknown>): never {
  if (status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  if (status === 403) throw new BffError("FORBIDDEN", 403, "Evidence export is not permitted.");
  if (status === 404) throw new BffError("NOT_FOUND", 404, "The protection request was not found.");
  if (status === 400 || typeof body.code === "string") {
    throw new BffError("INVALID_REQUEST", 400, "The evidence export request is invalid.");
  }
  throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Evidence export is temporarily unavailable.", true);
}

export interface EvidenceExportClientConfig {
  origin: string;
  operatorToken: string;
  timeoutMs: number;
  maxResponseBytes: number;
}

export class AccountShieldEvidenceExportClient implements EvidenceExportService {
  constructor(private readonly config: EvidenceExportClientConfig) {}

  async export(
    input: EvidenceExportInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<EvidenceBundle> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);
    if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true });
    try {
      const response = await fetch(`${this.config.origin}/api/v1/evidence/export`, {
        method: "POST",
        headers: {
          accept: "application/json",
          authorization: `Bearer ${this.config.operatorToken}`,
          "content-type": "application/json",
          "x-correlation-id": correlationId,
        },
        body: JSON.stringify({
          protectionRequestId: input.protectionRequestId,
          reason: input.reason,
        }),
        cache: "no-store",
        signal: controller.signal,
      });

      const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
      let body: unknown = {};
      if (contentType === "application/json" || contentType === "application/problem+json") {
        const declaredLength = Number.parseInt(response.headers.get("content-length") ?? "", 10);
        if (Number.isFinite(declaredLength) && declaredLength > this.config.maxResponseBytes) malformed();
        const raw = new Uint8Array(await response.arrayBuffer());
        if (raw.byteLength > this.config.maxResponseBytes) malformed();
        try {
          body = JSON.parse(new TextDecoder().decode(raw));
        } catch (error) {
          throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
            cause: error,
          });
        }
      }

      if (!response.ok) mapUpstreamFailure(response.status, isRecord(body) ? body : {});
      return parseEvidenceBundle(body);
    } catch (error) {
      if (error instanceof BffError) throw error;
      if (controller.signal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "Evidence export timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Evidence export is temporarily unavailable.", true, {
        cause: error,
      });
    } finally {
      clearTimeout(timeout);
    }
  }
}
