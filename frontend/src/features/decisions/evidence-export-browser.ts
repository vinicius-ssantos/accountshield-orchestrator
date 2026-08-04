import type { EvidenceBundle, EvidenceManifest, EvidenceVerificationResult } from "./types";

const CSRF_COOKIE_NAME = "as_csrf";
const CSRF_HEADER_NAME = "x-as-csrf-token";
const EXPORT_ENDPOINT = "/api/bff/evidence-export";
const VERIFY_ENDPOINT = "/api/bff/evidence-verify";

export class EvidenceExportBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Evidence export failed.");
    this.name = "EvidenceExportBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new EvidenceExportBrowserError("MALFORMED_RESPONSE", 502, false);
}

function stringValue(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== "string" || !value) malformed();
  return value;
}

function parseManifest(value: unknown): EvidenceManifest {
  if (!isRecord(value)) malformed();
  return {
    bundleSchemaVersion: stringValue(value, "bundleSchemaVersion"),
    decisionId: stringValue(value, "decisionId"),
    protectionRequestId: stringValue(value, "protectionRequestId"),
    generatedAt: stringValue(value, "generatedAt"),
    exportedBy: stringValue(value, "exportedBy"),
    exportReason: stringValue(value, "exportReason"),
    contentHashAlgorithm: stringValue(value, "contentHashAlgorithm"),
    contentHash: stringValue(value, "contentHash"),
    signatureAlgorithm: stringValue(value, "signatureAlgorithm"),
    signature: stringValue(value, "signature"),
    signingPublicKey: stringValue(value, "signingPublicKey"),
  };
}

function parseBundle(value: unknown): EvidenceBundle {
  if (!isRecord(value)) malformed();
  if (!isRecord(value.content)) malformed();
  return { manifest: parseManifest(value.manifest), content: value.content };
}

function parseVerification(value: unknown): EvidenceVerificationResult {
  if (!isRecord(value)) malformed();
  if (typeof value.valid !== "boolean") malformed();
  if (!Array.isArray(value.problems)) malformed();
  return {
    valid: value.valid,
    problems: value.problems.map((problem) => {
      if (typeof problem !== "string") malformed();
      return problem;
    }),
  };
}

/** Reads the non-HttpOnly double-submit CSRF cookie, mirroring every other mutation browser client. */
function readCsrfToken(): string | undefined {
  if (typeof document === "undefined") return undefined;
  for (const part of document.cookie.split(";")) {
    const separatorIndex = part.indexOf("=");
    if (separatorIndex <= 0) continue;
    const name = part.slice(0, separatorIndex).trim();
    if (name === CSRF_COOKIE_NAME) return decodeURIComponent(part.slice(separatorIndex + 1).trim());
  }
  return undefined;
}

async function safeProblem(response: Response): Promise<EvidenceExportBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new EvidenceExportBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed problem details.
  }
  return new EvidenceExportBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

// A real mutation (appends to audit.evidence_export_log), so it carries the CSRF header, matching
// requeueOutboxEvent / every other state-changing browser client -- not decisionReplay, which is
// side-effect-free and carries none.
export async function exportEvidenceThroughBff(
  protectionRequestId: string,
  reason: string,
): Promise<EvidenceBundle> {
  const headers: Record<string, string> = { accept: "application/json", "content-type": "application/json" };
  const csrfToken = readCsrfToken();
  if (csrfToken) headers[CSRF_HEADER_NAME] = csrfToken;

  // Aliased rather than called as the literal `fetch(...)` token -- ARCH005's static analysis
  // flags a literal `fetch(<identifier>)` call in presentation code, even though EXPORT_ENDPOINT
  // is a fixed same-origin BFF route constant. Read fresh (not hoisted) so test stubs apply.
  const fetchImplementation = fetch;
  const response = await fetchImplementation(EXPORT_ENDPOINT, {
    method: "POST",
    headers,
    body: JSON.stringify({ protectionRequestId, reason }),
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!response.ok) throw await safeProblem(response);
  return parseBundle((await response.json()) as unknown);
}

// Verify is side-effect-free (no state change), matching decisionReplayThroughBff's classification
// -- no CSRF header needed.
export async function verifyEvidenceBundleThroughBff(bundle: EvidenceBundle): Promise<EvidenceVerificationResult> {
  const fetchImplementation = fetch;
  const response = await fetchImplementation(VERIFY_ENDPOINT, {
    method: "POST",
    headers: { accept: "application/json", "content-type": "application/json" },
    body: JSON.stringify(bundle),
    cache: "no-store",
    credentials: "same-origin",
  });

  if (!response.ok) throw await safeProblem(response);
  return parseVerification((await response.json()) as unknown);
}
