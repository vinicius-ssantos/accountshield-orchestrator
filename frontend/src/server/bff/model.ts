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
  | "UNAUTHORIZED"
  | "FORBIDDEN"
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
