"use client";

import { useState } from "react";

import { SafeAlert, StatusBadge } from "@/design-system/components";

import {
  RecoveryReviewBrowserError,
  requestReviewStepUp,
  submitRecoveryReview,
  verifyReviewStepUp,
  type RecoveryReviewDecision,
} from "./recovery-review-browser";

type ReviewStage =
  | { name: "idle" }
  | { name: "step-up-requested"; challengeId: string; simulatedCode: string | null }
  | { name: "verifying" }
  | { name: "verified"; challengeId: string }
  | { name: "submitting"; decision: RecoveryReviewDecision }
  | { name: "done"; decision: RecoveryReviewDecision; status: string }
  | { name: "error"; message: string; retryable: boolean };

function errorMessage(error: unknown): { message: string; retryable: boolean } {
  if (error instanceof RecoveryReviewBrowserError) {
    if (error.status === 401) {
      return { message: "Your operator session is no longer valid. Sign in again to continue.", retryable: false };
    }
    if (error.status === 403) {
      return { message: "Recovery review is not permitted for the authenticated operator.", retryable: false };
    }
    if (error.code === "CONFLICT") {
      return { message: "This recovery was already reviewed by another operator.", retryable: false };
    }
    if (error.status === 400) {
      return { message: "The simulated code was not accepted. Request a new step-up and try again.", retryable: true };
    }
  }
  return { message: "Recovery review is temporarily unavailable. No sensitive detail was exposed.", retryable: true };
}

export function RecoveryReviewPanel({
  recoveryReference,
  onReviewed,
}: {
  recoveryReference: string;
  onReviewed: () => void;
}) {
  const [stage, setStage] = useState<ReviewStage>({ name: "idle" });
  const [code, setCode] = useState("");

  async function startStepUp() {
    setStage({ name: "verifying" });
    try {
      const challenge = await requestReviewStepUp(recoveryReference);
      setCode(challenge.simulatedCode ?? "");
      setStage({ name: "step-up-requested", challengeId: challenge.challengeId, simulatedCode: challenge.simulatedCode });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function verify(challengeId: string) {
    setStage({ name: "verifying" });
    try {
      const result = await verifyReviewStepUp(recoveryReference, challengeId, code);
      if (!result.verified) {
        setStage({
          name: "error",
          message: `The code was not verified (${result.remainingAttempts} attempt(s) remaining).`,
          retryable: result.remainingAttempts > 0,
        });
        return;
      }
      setStage({ name: "verified", challengeId });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submit(decision: RecoveryReviewDecision, challengeId: string) {
    setStage({ name: "submitting", decision });
    try {
      const result = await submitRecoveryReview(recoveryReference, decision, challengeId);
      setStage({ name: "done", decision, status: result.status });
      onReviewed();
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  return (
    <section className="investigationSection" aria-labelledby="recovery-review-heading">
      <div className="investigationSectionHeader">
        <h3 id="recovery-review-heading">Review recovery</h3>
      </div>

      <SafeAlert title="Simulated step-up" tone="info">
        This deployment uses simulated challenge providers (ADR 0004) — there is no real MFA
        device or out-of-band channel. The code below is disclosed directly because a real
        delivery channel does not exist in this portfolio.
      </SafeAlert>

      {stage.name === "idle" ? (
        <button className="button button--secondary" type="button" onClick={() => void startStepUp()}>
          Start review (request step-up)
        </button>
      ) : null}

      {stage.name === "verifying" ? <p className="muted">Working…</p> : null}

      {stage.name === "step-up-requested" ? (
        <div className="investigationRecord">
          <label htmlFor="recovery-review-code">Simulated code</label>
          <input
            id="recovery-review-code"
            type="text"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            maxLength={64}
          />
          <button
            className="button button--secondary"
            type="button"
            onClick={() => void verify(stage.challengeId)}
            disabled={code.length === 0}
          >
            Verify code
          </button>
        </div>
      ) : null}

      {stage.name === "verified" ? (
        <div className="investigationRecord">
          <StatusBadge label="step-up verified" tone="positive" />
          <button className="button button--primary" type="button" onClick={() => void submit("APPROVE", stage.challengeId)}>
            Approve recovery
          </button>
          <button className="button button--secondary" type="button" onClick={() => void submit("REJECT", stage.challengeId)}>
            Reject recovery
          </button>
        </div>
      ) : null}

      {stage.name === "submitting" ? <p className="muted">Submitting {stage.decision.toLowerCase()}…</p> : null}

      {stage.name === "done" ? (
        <SafeAlert title={`Recovery ${stage.decision === "APPROVE" ? "approved" : "rejected"}`} tone="positive">
          New status: {stage.status}
        </SafeAlert>
      ) : null}

      {stage.name === "error" ? (
        <SafeAlert title="Review failed" tone="critical">
          {stage.message}
          {stage.retryable ? (
            <>
              {" "}
              <button className="actionLink" type="button" onClick={() => setStage({ name: "idle" })}>
                Try again
              </button>
            </>
          ) : null}
        </SafeAlert>
      ) : null}
    </section>
  );
}
