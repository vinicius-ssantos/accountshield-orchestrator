import { BffError } from "./foundation";

const KEY_PATTERN = /^[A-Za-z0-9._-]{1,100}$/;
const VERSION_PATTERN = /^[A-Za-z0-9._-]{1,40}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type PolicyRolloutLifecycleStatus = "ACTIVE" | "ROLLED_BACK";

export interface StepUpResult {
  challengeId: string;
  /** Disclosed only because this deployment uses ADR 0004's simulated challenge providers; null
   * when simulation is disabled backend-side. Never a real out-of-band delivery. */
  simulatedCode: string | null;
  /** Required by POST /api/v1/challenges/{id}/verify -- rollout step-up binds to a synthetic,
   * server-derived context, mirroring policy lifecycle's step-up design. */
  contextId: string;
}

export interface RolloutSummary {
  policyKey: string;
  candidateVersion: string;
  rolloutPercentage: number;
  status: PolicyRolloutLifecycleStatus;
  startedAt: string;
  startedBy: string;
  updatedAt: string;
  rolledBackAt: string | null;
  rolledBackBy: string | null;
}

export interface StartRolloutStepUpInput {
  policyKey: string;
  candidateVersion: string;
}

export interface StartRolloutInput {
  policyKey: string;
  candidateVersion: string;
  rolloutPercentage: number;
  stepUpChallengeId: string;
}

export interface PercentageStepUpInput {
  policyKey: string;
}

export interface UpdatePercentageInput {
  policyKey: string;
  rolloutPercentage: number;
  stepUpChallengeId: string;
}

export interface RollbackInput {
  policyKey: string;
}

export interface PolicyRolloutService {
  requestStartStepUp(
    input: StartRolloutStepUpInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult>;
  startRollout(input: StartRolloutInput, correlationId: string, signal?: AbortSignal): Promise<RolloutSummary>;
  requestPercentageStepUp(
    input: PercentageStepUpInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult>;
  updatePercentage(
    input: UpdatePercentageInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RolloutSummary>;
  rollback(input: RollbackInput, correlationId: string, signal?: AbortSignal): Promise<RolloutSummary>;
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
    throw new BffError("INVALID_REQUEST", 400, "candidateVersion must be a valid policy version.");
  }
  return value;
}

function assertUuid(value: unknown, field: string): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new BffError("INVALID_REQUEST", 400, `${field} must be a valid identifier.`);
  }
  return value;
}

function assertPercentage(value: unknown): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > 100) {
    throw new BffError("INVALID_REQUEST", 400, "rolloutPercentage must be an integer from 0 to 100.");
  }
  return value;
}

export function parseStartRolloutStepUpInput(body: Record<string, unknown>): StartRolloutStepUpInput {
  return { policyKey: assertKey(body.policyKey), candidateVersion: assertVersion(body.candidateVersion) };
}

export function parseStartRolloutInput(body: Record<string, unknown>): StartRolloutInput {
  return {
    policyKey: assertKey(body.policyKey),
    candidateVersion: assertVersion(body.candidateVersion),
    rolloutPercentage: assertPercentage(body.rolloutPercentage),
    stepUpChallengeId: assertUuid(body.stepUpChallengeId, "stepUpChallengeId"),
  };
}

export function parsePercentageStepUpInput(body: Record<string, unknown>): PercentageStepUpInput {
  return { policyKey: assertKey(body.policyKey) };
}

export function parseUpdatePercentageInput(body: Record<string, unknown>): UpdatePercentageInput {
  return {
    policyKey: assertKey(body.policyKey),
    rolloutPercentage: assertPercentage(body.rolloutPercentage),
    stepUpChallengeId: assertUuid(body.stepUpChallengeId, "stepUpChallengeId"),
  };
}

export function parseRollbackInput(body: Record<string, unknown>): RollbackInput {
  return { policyKey: assertKey(body.policyKey) };
}

export interface PolicyRolloutClientConfig {
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

// Rollout's own problem catalog (ROLLOUT_ALREADY_ACTIVE, ROLLOUT_CANDIDATE_NOT_APPROVED,
// POLICY_ROLLOUT_NOT_FOUND) is distinct from policy lifecycle's -- there is no self-approval or
// generic illegal-transition concept for a canary rollout, so SELF_APPROVAL_NOT_ALLOWED/
// ILLEGAL_TRANSITION are not reused here. The step-up-specific 409/410s reuse CONFLICT with
// `retryable`, matching policy-lifecycle-core.ts's convention.
function mapUpstreamFailure(status: number, body: Record<string, unknown>): never {
  const upstreamCode = typeof body.code === "string" ? body.code : undefined;
  if (upstreamCode === "ROLLOUT_ALREADY_ACTIVE") {
    throw new BffError("ROLLOUT_ALREADY_ACTIVE", status, "This policy already has an active rollout.");
  }
  if (upstreamCode === "ROLLOUT_CANDIDATE_NOT_APPROVED") {
    throw new BffError(
      "ROLLOUT_CANDIDATE_NOT_APPROVED",
      status,
      "The candidate version must be APPROVED before it can enter rollout.",
    );
  }
  if (upstreamCode === "POLICY_ROLLOUT_NOT_FOUND" || upstreamCode === "POLICY_VERSION_NOT_FOUND") {
    throw new BffError("NOT_FOUND", status, "The policy rollout or candidate version was not found.");
  }
  if (upstreamCode === "INVALID_CHALLENGE_STATE" || upstreamCode === "CHALLENGE_USE_REJECTED") {
    throw new BffError("CONFLICT", status, "The step-up challenge could not authorize this action.", true);
  }
  if (status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  if (status === 403) throw new BffError("FORBIDDEN", 403, "Policy rollout actions are not permitted.");
  if (status === 400) throw new BffError("INVALID_REQUEST", 400, "The policy rollout request was rejected.");
  if (status === 404) throw new BffError("NOT_FOUND", 404, "The policy rollout or challenge was not found.");
  if (status === 409) throw new BffError("CONFLICT", 409, "The policy rollout state has since changed.");
  throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Policy rollout actions are temporarily unavailable.", true);
}

function toRolloutSummary(body: Record<string, unknown>): RolloutSummary {
  if (
    typeof body.policyKey !== "string" ||
    typeof body.candidateVersion !== "string" ||
    typeof body.rolloutPercentage !== "number" ||
    (body.status !== "ACTIVE" && body.status !== "ROLLED_BACK") ||
    typeof body.startedAt !== "string" ||
    typeof body.startedBy !== "string" ||
    typeof body.updatedAt !== "string"
  ) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  return {
    policyKey: body.policyKey,
    candidateVersion: body.candidateVersion,
    rolloutPercentage: body.rolloutPercentage,
    status: body.status,
    startedAt: body.startedAt,
    startedBy: body.startedBy,
    updatedAt: body.updatedAt,
    rolledBackAt: typeof body.rolledBackAt === "string" ? body.rolledBackAt : null,
    rolledBackBy: typeof body.rolledBackBy === "string" ? body.rolledBackBy : null,
  };
}

export class AccountShieldPolicyRolloutClient implements PolicyRolloutService {
  constructor(private readonly config: PolicyRolloutClientConfig) {}

  private async request(
    method: "POST" | "PATCH",
    path: string,
    body: Record<string, unknown> | undefined,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<Record<string, unknown>> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);
    if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true });
    try {
      const response = await fetch(`${this.config.origin}${path}`, {
        method,
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${this.config.operatorToken}`,
          "x-correlation-id": correlationId,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      const parsed = await readJson(response);
      if (!response.ok) mapUpstreamFailure(response.status, parsed);
      return parsed;
    } catch (error) {
      if (error instanceof BffError) throw error;
      if (controller.signal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "Policy rollout action timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Policy rollout actions are temporarily unavailable.", true, {
        cause: error,
      });
    } finally {
      clearTimeout(timeout);
    }
  }

  private parseStepUpResponse(body: Record<string, unknown>): StepUpResult {
    if (typeof body.challengeId !== "string" || typeof body.contextId !== "string") {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }
    return {
      challengeId: body.challengeId,
      simulatedCode: typeof body.simulatedCode === "string" ? body.simulatedCode : null,
      contextId: body.contextId,
    };
  }

  async requestStartStepUp(
    input: StartRolloutStepUpInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult> {
    const body = await this.request(
      "POST",
      `/api/v1/policies/${input.policyKey}/rollout/step-up`,
      { candidateVersion: input.candidateVersion },
      correlationId,
      signal,
    );
    return this.parseStepUpResponse(body);
  }

  async startRollout(input: StartRolloutInput, correlationId: string, signal?: AbortSignal): Promise<RolloutSummary> {
    const body = await this.request(
      "POST",
      `/api/v1/policies/${input.policyKey}/rollout`,
      {
        candidateVersion: input.candidateVersion,
        rolloutPercentage: input.rolloutPercentage,
        stepUpChallengeId: input.stepUpChallengeId,
      },
      correlationId,
      signal,
    );
    return toRolloutSummary(body);
  }

  async requestPercentageStepUp(
    input: PercentageStepUpInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult> {
    const body = await this.request(
      "PATCH",
      `/api/v1/policies/${input.policyKey}/rollout/step-up`,
      undefined,
      correlationId,
      signal,
    );
    return this.parseStepUpResponse(body);
  }

  async updatePercentage(
    input: UpdatePercentageInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RolloutSummary> {
    const body = await this.request(
      "PATCH",
      `/api/v1/policies/${input.policyKey}/rollout`,
      { rolloutPercentage: input.rolloutPercentage, stepUpChallengeId: input.stepUpChallengeId },
      correlationId,
      signal,
    );
    return toRolloutSummary(body);
  }

  async rollback(input: RollbackInput, correlationId: string, signal?: AbortSignal): Promise<RolloutSummary> {
    const body = await this.request(
      "POST",
      `/api/v1/policies/${input.policyKey}/rollout/rollback`,
      undefined,
      correlationId,
      signal,
    );
    return toRolloutSummary(body);
  }
}
