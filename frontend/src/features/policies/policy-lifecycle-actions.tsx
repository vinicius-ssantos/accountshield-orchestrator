"use client";

import { useState } from "react";

import { SafeAlert, StatusBadge } from "@/design-system/components";

import {
  PolicyLifecycleBrowserError,
  activatePolicyVersion,
  approvePolicyVersion,
  rejectPolicyVersion,
  requestLifecycleStepUp,
  retirePolicyVersion,
  verifyLifecycleStepUp,
  type PolicyLifecycleAction,
} from "./policy-lifecycle-browser";
import type { PolicyGovernance, PolicyLifecycleStatus } from "./types";

type ActionName = PolicyLifecycleAction | "REJECT";

type Stage =
  | { name: "idle" }
  | { name: "reason-input" }
  | { name: "step-up-requested"; action: PolicyLifecycleAction; challengeId: string; contextId: string; reason?: string }
  | { name: "verifying" }
  | { name: "verified"; action: PolicyLifecycleAction; challengeId: string; reason?: string }
  | { name: "submitting"; action: ActionName }
  | { name: "done"; action: ActionName; status: string }
  | { name: "error"; message: string; retryable: boolean };

function actionLabel(action: ActionName): string {
  if (action === "APPROVE") return "approved";
  if (action === "ACTIVATE") return "activated";
  if (action === "RETIRE") return "retired";
  return "rejected";
}

function errorMessage(error: unknown): { message: string; retryable: boolean } {
  if (error instanceof PolicyLifecycleBrowserError) {
    if (error.status === 401) {
      return { message: "Your operator session is no longer valid. Sign in again to continue.", retryable: false };
    }
    if (error.status === 403) {
      return { message: "This policy lifecycle action is not permitted for the authenticated operator.", retryable: false };
    }
    if (error.code === "SELF_APPROVAL_NOT_ALLOWED") {
      return { message: "You authored this version and cannot approve it yourself.", retryable: false };
    }
    if (error.code === "ILLEGAL_TRANSITION") {
      return {
        message: "This version's status has since changed and no longer supports this action.",
        retryable: false,
      };
    }
    if (error.code === "NOT_FOUND") {
      return { message: "This policy version or step-up challenge could not be found.", retryable: false };
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

export function PolicyLifecycleActions({
  policyKey,
  version,
  status,
  governance,
  currentSubject,
  onChanged,
}: {
  policyKey: string;
  version: string;
  status: PolicyLifecycleStatus;
  governance: PolicyGovernance | null;
  /** The authenticated operator's subject, threaded down from the app layer (features must not
   * import features/session directly -- ARCH009) purely for a UX self-approval hint; the backend
   * independently enforces the real rejection either way. */
  currentSubject: string | undefined;
  onChanged: () => void;
}) {
  const [stage, setStage] = useState<Stage>({ name: "idle" });
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");

  const isAuthor = Boolean(currentSubject) && governance?.createdBy === currentSubject;

  const availableActions: PolicyLifecycleAction[] = [];
  if (status === "VALIDATED") availableActions.push("APPROVE");
  if (status === "APPROVED") availableActions.push("ACTIVATE");
  if (status === "ACTIVE") availableActions.push("RETIRE");
  const canReject = status === "DRAFT" || status === "VALIDATED" || status === "APPROVED";

  if (availableActions.length === 0 && !canReject) return null;

  async function startStepUp(action: PolicyLifecycleAction, actionReason?: string) {
    setStage({ name: "verifying" });
    try {
      const challenge = await requestLifecycleStepUp(action, policyKey, version);
      setCode(challenge.simulatedCode ?? "");
      setStage({
        name: "step-up-requested",
        action,
        challengeId: challenge.challengeId,
        contextId: challenge.contextId,
        reason: actionReason,
      });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function verify(action: PolicyLifecycleAction, challengeId: string, contextId: string, actionReason?: string) {
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
      setStage({ name: "verified", action, challengeId, reason: actionReason });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submitStepUpAction(action: PolicyLifecycleAction, challengeId: string, actionReason?: string) {
    setStage({ name: "submitting", action });
    try {
      const result =
        action === "APPROVE"
          ? await approvePolicyVersion(policyKey, version, challengeId, actionReason ?? "")
          : action === "ACTIVATE"
            ? await activatePolicyVersion(policyKey, version, challengeId)
            : await retirePolicyVersion(policyKey, version, challengeId);
      setStage({ name: "done", action, status: result.status });
      onChanged();
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submitReject() {
    setStage({ name: "submitting", action: "REJECT" });
    try {
      const result = await rejectPolicyVersion(policyKey, version);
      setStage({ name: "done", action: "REJECT", status: result.status });
      onChanged();
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  return (
    <section className="investigationSection" aria-labelledby={`policy-lifecycle-actions-${policyKey}-${version}`}>
      <div className="investigationSectionHeader">
        <h4 id={`policy-lifecycle-actions-${policyKey}-${version}`}>Manage {version}</h4>
      </div>

      {(availableActions.includes("APPROVE") || availableActions.includes("ACTIVATE") || availableActions.includes("RETIRE")) &&
      stage.name === "idle" ? (
        <SafeAlert title="Simulated step-up" tone="info">
          This deployment uses simulated challenge providers (ADR 0004) — there is no real MFA
          device or out-of-band channel. The code is disclosed directly because a real delivery
          channel does not exist in this portfolio.
        </SafeAlert>
      ) : null}

      {stage.name === "idle" ? (
        <div className="investigationRecord">
          {availableActions.includes("APPROVE") ? (
            isAuthor ? (
              <SafeAlert title="Self-approval not allowed" tone="attention">
                You authored this version and cannot approve it — a different operator must
                review it.
              </SafeAlert>
            ) : (
              <button
                className="button button--primary"
                type="button"
                onClick={() => setStage({ name: "reason-input" })}
              >
                Approve
              </button>
            )
          ) : null}
          {availableActions.includes("ACTIVATE") ? (
            <button
              className="button button--primary"
              type="button"
              onClick={() => void startStepUp("ACTIVATE")}
            >
              Activate
            </button>
          ) : null}
          {availableActions.includes("RETIRE") ? (
            <button
              className="button button--secondary"
              type="button"
              onClick={() => void startStepUp("RETIRE")}
            >
              Retire
            </button>
          ) : null}
          {canReject ? (
            <button className="button button--secondary" type="button" onClick={() => void submitReject()}>
              Reject
            </button>
          ) : null}
        </div>
      ) : null}

      {stage.name === "reason-input" ? (
        <div className="investigationRecord">
          <label htmlFor={`policy-approve-reason-${policyKey}-${version}`}>Reason for approval</label>
          <input
            id={`policy-approve-reason-${policyKey}-${version}`}
            type="text"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={500}
          />
          <button
            className="button button--primary"
            type="button"
            disabled={reason.trim().length === 0}
            onClick={() => void startStepUp("APPROVE", reason)}
          >
            Continue to step-up
          </button>
        </div>
      ) : null}

      {stage.name === "verifying" ? <p className="muted">Working…</p> : null}

      {stage.name === "step-up-requested" ? (
        <div className="investigationRecord">
          <label htmlFor={`policy-lifecycle-code-${policyKey}-${version}`}>Simulated code</label>
          <input
            id={`policy-lifecycle-code-${policyKey}-${version}`}
            type="text"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            maxLength={64}
          />
          <button
            className="button button--secondary"
            type="button"
            onClick={() => void verify(stage.action, stage.challengeId, stage.contextId, stage.reason)}
            disabled={code.length === 0}
          >
            Verify code
          </button>
        </div>
      ) : null}

      {stage.name === "verified" ? (
        <div className="investigationRecord">
          <StatusBadge label="step-up verified" tone="positive" />
          <button
            className="button button--primary"
            type="button"
            onClick={() => void submitStepUpAction(stage.action, stage.challengeId, stage.reason)}
          >
            Confirm {stage.action.toLowerCase()}
          </button>
        </div>
      ) : null}

      {stage.name === "submitting" ? <p className="muted">Submitting {actionLabel(stage.action)}…</p> : null}

      {stage.name === "done" ? (
        <SafeAlert title={`Policy version ${actionLabel(stage.action)}`} tone="positive">
          New status: {stage.status}
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
