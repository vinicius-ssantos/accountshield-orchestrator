export type RuntimeStatusSource = "fixtures" | "live";
export type RuntimeAvailability = "available" | "degraded";

export interface RuntimeStatusView {
  availability: RuntimeAvailability;
  source: RuntimeStatusSource;
  checkedAt: string;
  correlationId: string;
}

export type BffErrorCode =
  | "METHOD_NOT_ALLOWED"
  | "UNSUPPORTED_MEDIA_TYPE"
  | "PAYLOAD_TOO_LARGE"
  | "INVALID_REQUEST"
  | "CONFLICT"
  // Distinct from CONFLICT because the UI explains and reacts to them differently (a specific,
  // actionable message rather than a generic "state changed, retry" fallback) -- not policy
  // lifecycle-specific vocabulary, reusable by any future mutation with the same shape of
  // maker-checker or state-machine rejection.
  | "SELF_APPROVAL_NOT_ALLOWED"
  | "ILLEGAL_TRANSITION"
  | "RATE_LIMITED"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "UPSTREAM_TIMEOUT"
  | "UPSTREAM_UNAVAILABLE"
  | "UPSTREAM_MALFORMED_RESPONSE"
  | "INTERNAL_ERROR";

export interface BffProblemDetails {
  type: string;
  title: string;
  status: number;
  code: BffErrorCode;
  correlationId: string;
  retryable: boolean;
}
