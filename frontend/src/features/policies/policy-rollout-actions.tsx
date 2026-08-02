"use client";

import { useState } from "react";

import { SafeAlert, StatusBadge, Timestamp } from "@/design-system/components";

// Rollout step-up verification reuses policy lifecycle's /api/bff/policy-lifecycle/verify route
// rather than duplicating one under policy-rollout: both ultimately forward to the same generic
// POST /api/v1/challenges/{id}/verify with purpose=PRIVILEGED_OPERATION, keyed only by the
// challengeId/contextId the corresponding .../step-up endpoint disclosed -- the backend does not
// distinguish "lifecycle" vs. "rollout" challenges beyond that.
import { verifyLifecycleStepUp } from "./policy-lifecycle-browser";
import {
  PolicyRolloutBrowserError,
  requestPercentageUpdateStepUp,
  requestStartRolloutStepUp,
  rollbackRollout,
  startRollout,
  updateRolloutPercentage,
  type RolloutSummary,
} from "./policy-rollout-browser";
import type { PolicyRolloutSummary } from "./types";

type MutationKind = "START" | "PERCENTAGE" | "ROLLBACK";

type Stage =
  | { name: "idle" }
  | { name: "start-input"; candidateVersion: string; percentage: number }
  | { name: "adjust-input"; percentage: number }
  | { name: "rollback-confirm" }
  | {
      name: "step-up-requested";
      kind: "START" | "PERCENTAGE";
      candidateVersion: string | undefined;
      percentage: number;
      challengeId: string;
      contextId: string;
      simulatedCode: string;
    }
  | { name: "verifying" }
  | {
      name: "verified";
      kind: "START" | "PERCENTAGE";
      candidateVersion: string | undefined;
      percentage: number;
      challengeId: string;
    }
  | { name: "submitting"; kind: MutationKind }
  | { name: "done"; kind: MutationKind; summary: RolloutSummary }
  | { name: "error"; message: string; retryable: boolean };

function errorMessage(error: unknown): { message: string; retryable: boolean } {
  if (error instanceof PolicyRolloutBrowserError) {
    if (error.status === 401) {
      return { message: "Your operator session is no longer valid. Sign in again to continue.", retryable: false };
    }
    if (error.status === 403) {
      return { message: "This policy rollout action is not permitted for the authenticated operator.", retryable: false };
    }
    if (error.code === "ROLLOUT_ALREADY_ACTIVE") {
      return { message: "This policy already has an active rollout.", retryable: false };
    }
    if (error.code === "ROLLOUT_CANDIDATE_NOT_APPROVED") {
      return { message: "The candidate version must be APPROVED before it can enter rollout.", retryable: false };
    }
    if (error.code === "NOT_FOUND") {
      return { message: "This policy rollout or step-up challenge could not be found.", retryable: false };
    }
    if (error.code === "CONFLICT" && error.status === 410) {
      return { message: "The step-up challenge expired. Request a new one.", retryable: true };
    }
    if (error.code === "CONFLICT") {
      return { message: "The simulated code was not accepted. Request a new step-up and try again.", retryable: true };
    }
  }
  return { message: "This action is temporarily unavailable. No sensitive detail was exposed.", retryable: true };
}

export function PolicyRolloutActions({
  policyKey,
  approvedCandidateVersions,
  activeRollout,
  onChanged,
}: {
  policyKey: string;
  /** Versions currently in APPROVED status -- the only ones eligible to start a new rollout. */
  approvedCandidateVersions: readonly string[];
  activeRollout: PolicyRolloutSummary | null;
  onChanged: () => void;
}) {
  const [stage, setStage] = useState<Stage>({ name: "idle" });

  if (approvedCandidateVersions.length === 0 && !activeRollout) return null;

  async function startStepUpForStart(candidateVersion: string, percentage: number) {
    setStage({ name: "verifying" });
    try {
      const challenge = await requestStartRolloutStepUp(policyKey, candidateVersion);
      setStage({
        name: "step-up-requested",
        kind: "START",
        candidateVersion,
        percentage,
        challengeId: challenge.challengeId,
        contextId: challenge.contextId,
        simulatedCode: challenge.simulatedCode ?? "",
      });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function startStepUpForPercentage(percentage: number) {
    setStage({ name: "verifying" });
    try {
      const challenge = await requestPercentageUpdateStepUp(policyKey);
      setStage({
        name: "step-up-requested",
        kind: "PERCENTAGE",
        candidateVersion: undefined,
        percentage,
        challengeId: challenge.challengeId,
        contextId: challenge.contextId,
        simulatedCode: challenge.simulatedCode ?? "",
      });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function verify(
    kind: "START" | "PERCENTAGE",
    candidateVersion: string | undefined,
    percentage: number,
    challengeId: string,
    contextId: string,
    code: string,
  ) {
    setStage({ name: "verifying" });
    try {
      const result = await verifyLifecycleStepUp(challengeId, contextId, code);
      if (!result.verified) {
        setStage({
          name: "error",
          message: `The code was not verified (${result.remainingAttempts} attempt(s) remaining).`,
          retryable: result.remainingAttempts > 0,
        });
        return;
      }
      setStage({ name: "verified", kind, candidateVersion, percentage, challengeId });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submitStart(candidateVersion: string, percentage: number, challengeId: string) {
    setStage({ name: "submitting", kind: "START" });
    try {
      const summary = await startRollout(policyKey, candidateVersion, percentage, challengeId);
      setStage({ name: "done", kind: "START", summary });
      onChanged();
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submitPercentage(percentage: number, challengeId: string) {
    setStage({ name: "submitting", kind: "PERCENTAGE" });
    try {
      const summary = await updateRolloutPercentage(policyKey, percentage, challengeId);
      setStage({ name: "done", kind: "PERCENTAGE", summary });
      onChanged();
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submitRollback() {
    setStage({ name: "submitting", kind: "ROLLBACK" });
    try {
      const summary = await rollbackRollout(policyKey);
      setStage({ name: "done", kind: "ROLLBACK", summary });
      onChanged();
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  return (
    <section className="investigationSection" aria-labelledby={`policy-rollout-actions-${policyKey}`}>
      <div className="investigationSectionHeader">
        <h4 id={`policy-rollout-actions-${policyKey}`}>Rollout controls</h4>
      </div>

      {stage.name === "idle" ? (
        <SafeAlert title="Simulated step-up" tone="info">
          This deployment uses simulated challenge providers (ADR 0004) -- there is no real MFA
          device or out-of-band channel. The code is disclosed directly because a real delivery
          channel does not exist in this portfolio. Rolling back does not require step-up and
          takes effect immediately.
        </SafeAlert>
      ) : null}

      {stage.name === "idle" ? (
        <div className="investigationRecord">
          {!activeRollout && approvedCandidateVersions.length > 0 ? (
            <button
              className="button button--primary"
              type="button"
              onClick={() =>
                setStage({ name: "start-input", candidateVersion: approvedCandidateVersions[0]!, percentage: 10 })
              }
            >
              Start rollout
            </button>
          ) : null}
          {activeRollout ? (
            <>
              <button
                className="button button--primary"
                type="button"
                onClick={() => setStage({ name: "adjust-input", percentage: activeRollout.rolloutPercentage })}
              >
                Adjust percentage
              </button>
              <button
                className="button button--secondary"
                type="button"
                onClick={() => setStage({ name: "rollback-confirm" })}
              >
                Roll back
              </button>
            </>
          ) : null}
        </div>
      ) : null}

      {stage.name === "start-input" ? (
        <div className="investigationRecord">
          <label htmlFor={`policy-rollout-candidate-${policyKey}`}>Candidate version</label>
          <select
            id={`policy-rollout-candidate-${policyKey}`}
            value={stage.candidateVersion}
            onChange={(event) => setStage({ ...stage, candidateVersion: event.target.value })}
          >
            {approvedCandidateVersions.map((version) => (
              <option key={version} value={version}>
                {version}
              </option>
            ))}
          </select>
          <label htmlFor={`policy-rollout-start-percentage-${policyKey}`}>Rollout percentage</label>
          <input
            id={`policy-rollout-start-percentage-${policyKey}`}
            type="number"
            min={0}
            max={100}
            value={stage.percentage}
            onChange={(event) => setStage({ ...stage, percentage: Number(event.target.value) })}
          />
          <button
            className="button button--primary"
            type="button"
            onClick={() => void startStepUpForStart(stage.candidateVersion, stage.percentage)}
          >
            Continue to step-up
          </button>
        </div>
      ) : null}

      {stage.name === "adjust-input" ? (
        <div className="investigationRecord">
          <label htmlFor={`policy-rollout-adjust-percentage-${policyKey}`}>New rollout percentage</label>
          <input
            id={`policy-rollout-adjust-percentage-${policyKey}`}
            type="number"
            min={0}
            max={100}
            value={stage.percentage}
            onChange={(event) => setStage({ ...stage, percentage: Number(event.target.value) })}
          />
          <button
            className="button button--primary"
            type="button"
            onClick={() => void startStepUpForPercentage(stage.percentage)}
          >
            Continue to step-up
          </button>
        </div>
      ) : null}

      {stage.name === "rollback-confirm" ? (
        <SafeAlert title="Roll back immediately?" tone="attention">
          <p>
            This takes effect immediately, with no step-up confirmation, and rolls the policy back
            to its current stable version right now. There is no undo -- starting a new rollout
            afterward requires the full step-up flow again.
          </p>
          <div className="investigationRecord">
            <button className="button button--critical" type="button" onClick={() => void submitRollback()}>
              Roll back now
            </button>
            <button className="button button--secondary" type="button" onClick={() => setStage({ name: "idle" })}>
              Cancel
            </button>
          </div>
        </SafeAlert>
      ) : null}

      {stage.name === "verifying" ? <p className="muted">Working…</p> : null}

      {stage.name === "step-up-requested" ? (
        <StepUpCodeForm
          policyKey={policyKey}
          initialCode={stage.simulatedCode}
          onVerify={(code) =>
            void verify(stage.kind, stage.candidateVersion, stage.percentage, stage.challengeId, stage.contextId, code)
          }
        />
      ) : null}

      {stage.name === "verified" ? (
        <div className="investigationRecord">
          <StatusBadge label="step-up verified" tone="positive" />
          <button
            className="button button--primary"
            type="button"
            onClick={() =>
              void (stage.kind === "START"
                ? submitStart(stage.candidateVersion!, stage.percentage, stage.challengeId)
                : submitPercentage(stage.percentage, stage.challengeId))
            }
          >
            Confirm {stage.kind === "START" ? "start rollout" : "percentage update"}
          </button>
        </div>
      ) : null}

      {stage.name === "submitting" ? <p className="muted">Submitting…</p> : null}

      {stage.name === "done" ? (
        <SafeAlert
          title={
            stage.kind === "START"
              ? "Rollout started"
              : stage.kind === "PERCENTAGE"
                ? "Rollout percentage updated"
                : "Rollout rolled back"
          }
          tone={stage.kind === "ROLLBACK" ? "attention" : "positive"}
        >
          {stage.kind === "ROLLBACK" ? (
            <span>Rolled back at <Timestamp value={stage.summary.rolledBackAt ?? stage.summary.updatedAt} /></span>
          ) : (
            <span>
              {stage.summary.candidateVersion} at {stage.summary.rolloutPercentage}%
            </span>
          )}
        </SafeAlert>
      ) : null}

      {stage.name === "error" ? (
        <SafeAlert title="Action failed" tone="critical">
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

function StepUpCodeForm({
  policyKey,
  initialCode,
  onVerify,
}: {
  policyKey: string;
  initialCode: string;
  onVerify: (code: string) => void;
}) {
  const [code, setCode] = useState(initialCode);
  return (
    <div className="investigationRecord">
      <label htmlFor={`policy-rollout-code-${policyKey}`}>Simulated code</label>
      <input
        id={`policy-rollout-code-${policyKey}`}
        type="text"
        value={code}
        onChange={(event) => setCode(event.target.value)}
        maxLength={64}
      />
      <button
        className="button button--secondary"
        type="button"
        onClick={() => onVerify(code)}
        disabled={code.length === 0}
      >
        Verify code
      </button>
    </div>
  );
}
