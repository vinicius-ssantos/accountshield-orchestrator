import { BffError } from "./foundation";

const REFERENCE_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_CODE_LENGTH = 64;

export interface StepUpRequestInput {
  recoveryReference: string;
}

export interface StepUpResult {
  challengeId: string;
  /** Disclosed only because this deployment uses ADR 0004's simulated challenge providers; null
   * when simulation is disabled backend-side. Never a real out-of-band delivery. */
  simulatedCode: string | null;
}

export interface VerifyStepUpInput {
  recoveryReference: string;
  challengeId: string;
  providedCode: string;
}

export interface VerifyStepUpResult {
  verified: boolean;
  status: string;
  remainingAttempts: number;
}

export type RecoveryReviewDecision = "APPROVE" | "REJECT";

export interface ReviewSubmissionInput {
  recoveryReference: string;
  decision: RecoveryReviewDecision;
  stepUpChallengeId: string;
}

export interface ReviewSubmissionResult {
  status: string;
}

export interface RecoveryReviewService {
  requestStepUp(input: StepUpRequestInput, correlationId: string, signal?: AbortSignal): Promise<StepUpResult>;
  verifyStepUp(input: VerifyStepUpInput, correlationId: string, signal?: AbortSignal): Promise<VerifyStepUpResult>;
  submitReview(
    input: ReviewSubmissionInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<ReviewSubmissionResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function assertReference(value: unknown): string {
  if (typeof value !== "string" || !REFERENCE_PATTERN.test(value)) {
    throw new BffError("INVALID_REQUEST", 400, "recoveryReference must be a valid reference.");
  }
  return value;
}

function assertBoundedString(value: unknown, field: string, maxLength: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw new BffError("INVALID_REQUEST", 400, `${field} is required and must be within bounds.`);
  }
  return value;
}

export function parseStepUpRequestInput(body: Record<string, unknown>): StepUpRequestInput {
  return { recoveryReference: assertReference(body.recoveryReference) };
}

export function parseVerifyStepUpInput(body: Record<string, unknown>): VerifyStepUpInput {
  return {
    recoveryReference: assertReference(body.recoveryReference),
    challengeId: assertReference(body.challengeId),
    providedCode: assertBoundedString(body.providedCode, "providedCode", MAX_CODE_LENGTH),
  };
}

export function parseReviewSubmissionInput(body: Record<string, unknown>): ReviewSubmissionInput {
  if (body.decision !== "APPROVE" && body.decision !== "REJECT") {
    throw new BffError("INVALID_REQUEST", 400, "decision must be APPROVE or REJECT.");
  }
  return {
    recoveryReference: assertReference(body.recoveryReference),
    decision: body.decision,
    stepUpChallengeId: assertReference(body.stepUpChallengeId),
  };
}

export interface RecoveryReviewClientConfig {
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

function mapUpstreamFailure(status: number): never {
  if (status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  if (status === 403) throw new BffError("FORBIDDEN", 403, "Recovery review is not permitted.");
  if (status === 400) throw new BffError("INVALID_REQUEST", 400, "The review request was rejected.");
  if (status === 404) throw new BffError("NOT_FOUND", 404, "The recovery or challenge was not found.");
  if (status === 409) {
    throw new BffError("CONFLICT", 409, "The recovery was already reviewed by another operator.");
  }
  throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Recovery review is temporarily unavailable.", true);
}

export class AccountShieldRecoveryReviewClient implements RecoveryReviewService {
  constructor(private readonly config: RecoveryReviewClientConfig) {}

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
      if (!response.ok) mapUpstreamFailure(response.status);
      return parsed;
    } catch (error) {
      if (error instanceof BffError) throw error;
      if (controller.signal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "Recovery review timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Recovery review is temporarily unavailable.", true, {
        cause: error,
      });
    } finally {
      clearTimeout(timeout);
    }
  }

  async requestStepUp(
    input: StepUpRequestInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<StepUpResult> {
    const body = await this.post(
      `/api/v1/recovery/${input.recoveryReference}/review/step-up`,
      {},
      correlationId,
      signal,
    );
    if (typeof body.challengeId !== "string") {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }
    return {
      challengeId: body.challengeId,
      simulatedCode: typeof body.simulatedCode === "string" ? body.simulatedCode : null,
    };
  }

  async verifyStepUp(
    input: VerifyStepUpInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<VerifyStepUpResult> {
    const body = await this.post(
      `/api/v1/challenges/${input.challengeId}/verify`,
      { providedCode: input.providedCode, purpose: "PRIVILEGED_OPERATION", contextId: input.recoveryReference },
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

  async submitReview(
    input: ReviewSubmissionInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<ReviewSubmissionResult> {
    const body = await this.post(
      `/api/v1/recovery/${input.recoveryReference}/review`,
      { decision: input.decision, stepUpChallengeId: input.stepUpChallengeId },
      correlationId,
      signal,
    );
    if (typeof body.status !== "string") {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }
    return { status: body.status };
  }
}
