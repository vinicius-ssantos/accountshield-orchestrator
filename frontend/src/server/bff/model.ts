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
  // Rollout's own vocabulary (issue #200): distinct from CONFLICT for the same reason as the
  // pair above -- specific, actionable UI messages rather than a generic "state changed, retry".
  | "ROLLOUT_ALREADY_ACTIVE"
  | "ROLLOUT_CANDIDATE_NOT_APPROVED"
  // Outbox requeue (issue #203) has no step-up gate -- it's operational remediation, not a
  // privileged security action -- but still benefits from a distinct, actionable message for its
  // one real conflict case: the event is no longer dead-lettered by the time requeue is attempted.
  | "OUTBOX_EVENT_NOT_DEAD_LETTERED"
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
