import { BffError } from "./foundation";

const KEY_PATTERN = /^[A-Za-z0-9._-]{1,100}$/;
const VERSION_PATTERN = /^[A-Za-z0-9._-]{1,40}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_CODE_LENGTH = 64;
const MAX_REASON_LENGTH = 500;

export type PolicyLifecycleAction = "APPROVE" | "ACTIVATE" | "RETIRE";

export interface PolicyVersionRef {
  policyKey: string;
  version: string;
}

export interface StepUpResult {
  challengeId: string;
  /** Disclosed only because this deployment uses ADR 0004's simulated challenge providers; null
   * when simulation is disabled backend-side. Never a real out-of-band delivery. */
  simulatedCode: string | null;
  /** Required by POST /api/v1/challenges/{id}/verify -- policy step-up binds to a synthetic,
   * server-derived context, unlike recovery review's, which reuses a natural identifier. */
  contextId: string;
}

export interface VerifyStepUpInput {
  challengeId: string;
  contextId: string;
  providedCode: string;
}

export interface VerifyStepUpResult {
  verified: boolean;
  status: string;
  remainingAttempts: number;
}

export interface ApproveInput extends PolicyVersionRef {
  stepUpChallengeId: string;
  reason: string;
}

export interface StepUpChallengeInput extends PolicyVersionRef {
  stepUpChallengeId: string;
}

export interface LifecycleActionResult {
  status: string;
}

export interface PolicyLifecycleService {
  requestStepUp(
    action: PolicyLifecycleAction,
    ref: PolicyVersionRef,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult>;
  verifyStepUp(input: VerifyStepUpInput, correlationId: string, signal?: AbortSignal): Promise<VerifyStepUpResult>;
  approve(input: ApproveInput, correlationId: string, signal?: AbortSignal): Promise<LifecycleActionResult>;
  activate(input: StepUpChallengeInput, correlationId: string, signal?: AbortSignal): Promise<LifecycleActionResult>;
  reject(ref: PolicyVersionRef, correlationId: string, signal?: AbortSignal): Promise<LifecycleActionResult>;
  retire(input: StepUpChallengeInput, correlationId: string, signal?: AbortSignal): Promise<LifecycleActionResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function assertKey(value: unknown): string {
  if (typeof value !== "string" || !KEY_PATTERN.test(value)) {
    throw new BffError("INVALID_REQUEST", 400, "policyKey must be a valid policy key.");
  }
  return value;
}

function assertVersion(value: unknown): string {
  if (typeof value !== "string" || !VERSION_PATTERN.test(value)) {
    throw new BffError("INVALID_REQUEST", 400, "version must be a valid policy version.");
  }
  return value;
}

function assertUuid(value: unknown, field: string): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new BffError("INVALID_REQUEST", 400, `${field} must be a valid identifier.`);
  }
  return value;
}

function assertBoundedString(value: unknown, field: string, maxLength: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw new BffError("INVALID_REQUEST", 400, `${field} is required and must be within bounds.`);
  }
  return value;
}

function parseRef(body: Record<string, unknown>): PolicyVersionRef {
  return { policyKey: assertKey(body.policyKey), version: assertVersion(body.version) };
}

export function parseStepUpRequestInput(body: Record<string, unknown>): PolicyVersionRef {
  return parseRef(body);
}

export function parseVerifyStepUpInput(body: Record<string, unknown>): VerifyStepUpInput {
  return {
    challengeId: assertUuid(body.challengeId, "challengeId"),
    contextId: assertUuid(body.contextId, "contextId"),
    providedCode: assertBoundedString(body.providedCode, "providedCode", MAX_CODE_LENGTH),
  };
}

export function parseApproveInput(body: Record<string, unknown>): ApproveInput {
  const ref = parseRef(body);
  return {
    ...ref,
    stepUpChallengeId: assertUuid(body.stepUpChallengeId, "stepUpChallengeId"),
    reason: assertBoundedString(body.reason, "reason", MAX_REASON_LENGTH),
  };
}

export function parseStepUpChallengeInput(body: Record<string, unknown>): StepUpChallengeInput {
  const ref = parseRef(body);
  return { ...ref, stepUpChallengeId: assertUuid(body.stepUpChallengeId, "stepUpChallengeId") };
}

export function parseRejectInput(body: Record<string, unknown>): PolicyVersionRef {
  return parseRef(body);
}

export interface PolicyLifecycleClientConfig {
  origin: string;
  operatorToken: string;
  timeoutMs: number;
}

async function readJson(response: Response): Promise<Record<string, unknown>> {
  const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
  if (contentType !== "application/json" && contentType !== "application/problem+json") {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch (error) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
      cause: error,
    });
  }
  if (!isRecord(body)) throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  return body;
}

// Unlike recovery-review's pure status-based mapping, the backend's policy problem catalog
// differentiates enough (SELF_APPROVAL_NOT_ALLOWED, ILLEGAL_TRANSITION, INVALID_CHALLENGE_STATE,
// CHALLENGE_USE_REJECTED, POLICY_VERSION_NOT_FOUND) that forwarding the two truly distinct,
// differently-actionable cases produces a much more useful frontend error than collapsing every
// 409 into one generic conflict. The step-up-specific 409/410s reuse CONFLICT with `retryable`
// already carrying enough signal for the UI ("request a new step-up" vs. a hard stop).
function mapUpstreamFailure(status: number, body: Record<string, unknown>): never {
  const upstreamCode = typeof body.code === "string" ? body.code : undefined;
  if (upstreamCode === "SELF_APPROVAL_NOT_ALLOWED") {
    throw new BffError("SELF_APPROVAL_NOT_ALLOWED", status, "The authenticated operator authored this version.");
  }
  if (upstreamCode === "ILLEGAL_TRANSITION") {
    throw new BffError("ILLEGAL_TRANSITION", status, "The policy version state has since changed.");
  }
  if (upstreamCode === "POLICY_VERSION_NOT_FOUND") {
    throw new BffError("NOT_FOUND", status, "The policy version was not found.");
  }
  if (upstreamCode === "INVALID_CHALLENGE_STATE" || upstreamCode === "CHALLENGE_USE_REJECTED") {
    throw new BffError("CONFLICT", status, "The step-up challenge could not authorize this action.", true);
  }
  if (status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  if (status === 403) throw new BffError("FORBIDDEN", 403, "Policy lifecycle actions are not permitted.");
  if (status === 400) throw new BffError("INVALID_REQUEST", 400, "The policy lifecycle request was rejected.");
  if (status === 404) throw new BffError("NOT_FOUND", 404, "The policy version or challenge was not found.");
  if (status === 409) throw new BffError("CONFLICT", 409, "The policy version state has since changed.");
  throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Policy lifecycle actions are temporarily unavailable.", true);
}

const STEP_UP_ACTION_PATH: Record<PolicyLifecycleAction, string> = {
  APPROVE: "approve",
  ACTIVATE: "activate",
  RETIRE: "retire",
};

export class AccountShieldPolicyLifecycleClient implements PolicyLifecycleService {
  constructor(private readonly config: PolicyLifecycleClientConfig) {}

  private async post(
    path: string,
    body: Record<string, unknown>,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<Record<string, unknown>> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);
    if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true });
    try {
      const response = await fetch(`${this.config.origin}${path}`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${this.config.operatorToken}`,
          "x-correlation-id": correlationId,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const parsed = await readJson(response);
      if (!response.ok) mapUpstreamFailure(response.status, parsed);
      return parsed;
    } catch (error) {
      if (error instanceof BffError) throw error;
      if (controller.signal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "Policy lifecycle action timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Policy lifecycle actions are temporarily unavailable.", true, {
        cause: error,
      });
    } finally {
      clearTimeout(timeout);
    }
  }

  async requestStepUp(
    action: PolicyLifecycleAction,
    ref: PolicyVersionRef,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult> {
    const body = await this.post(
      `/api/v1/policies/${ref.policyKey}/${ref.version}/${STEP_UP_ACTION_PATH[action]}/step-up`,
      {},
      correlationId,
      signal,
    );
    if (typeof body.challengeId !== "string" || typeof body.contextId !== "string") {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }
    return {
      challengeId: body.challengeId,
      simulatedCode: typeof body.simulatedCode === "string" ? body.simulatedCode : null,
      contextId: body.contextId,
    };
  }

  async verifyStepUp(
    input: VerifyStepUpInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<VerifyStepUpResult> {
    const body = await this.post(
      `/api/v1/challenges/${input.challengeId}/verify`,
      { providedCode: input.providedCode, purpose: "PRIVILEGED_OPERATION", contextId: input.contextId },
      correlationId,
      signal,
    );
    if (typeof body.verified !== "boolean" || typeof body.status !== "string") {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }
    return {
      verified: body.verified,
      status: body.status,
      remainingAttempts: typeof body.remainingAttempts === "number" ? body.remainingAttempts : 0,
    };
  }

  private async lifecycleAction(
    ref: PolicyVersionRef,
    segment: string,
    body: Record<string, unknown>,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<LifecycleActionResult> {
    const responseBody = await this.post(
      `/api/v1/policies/${ref.policyKey}/${ref.version}/${segment}`,
      body,
      correlationId,
      signal,
    );
    if (typeof responseBody.status !== "string") {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }
    return { status: responseBody.status };
  }

  async approve(input: ApproveInput, correlationId: string, signal?: AbortSignal): Promise<LifecycleActionResult> {
    return this.lifecycleAction(
      input,
      "approve",
      { stepUpChallengeId: input.stepUpChallengeId, reason: input.reason },
      correlationId,
      signal,
    );
  }

  async activate(
    input: StepUpChallengeInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<LifecycleActionResult> {
    return this.lifecycleAction(
      input,
      "activate",
      { stepUpChallengeId: input.stepUpChallengeId },
      correlationId,
      signal,
    );
  }

  async reject(ref: PolicyVersionRef, correlationId: string, signal?: AbortSignal): Promise<LifecycleActionResult> {
    return this.lifecycleAction(ref, "reject", {}, correlationId, signal);
  }

  async retire(
    input: StepUpChallengeInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<LifecycleActionResult> {
    return this.lifecycleAction(
      input,
      "retire",
      { stepUpChallengeId: input.stepUpChallengeId },
      correlationId,
      signal,
    );
  }
}
