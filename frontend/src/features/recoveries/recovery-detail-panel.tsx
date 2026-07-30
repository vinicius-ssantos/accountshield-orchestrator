"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  ApplicationState,
  MaskedIdentifier,
  Panel,
  SafeAlert,
  SectionHeader,
  StatusBadge,
  Timestamp,
  type StatusTone,
} from "@/design-system/components";

import {
  RecoveryDetailBrowserError,
  investigateRecoveryThroughBff,
} from "./recovery-detail-browser";
import type { RecoveryInvestigationDetail, RecoverySectionAvailability } from "./types";

function statusTone(status: string): StatusTone {
  if (status === "COMPLETED") return "positive";
  if (status === "REJECTED" || status === "IDENTITY_FAILED" || status === "ABORTED") return "critical";
  if (status === "DELAYED" || status === "MANUAL_REVIEW") return "attention";
  return "info";
}

function availabilityTone(value: RecoverySectionAvailability): StatusTone {
  if (value === "AVAILABLE") return "positive";
  if (value === "NOT_APPLICABLE") return "muted";
  return "critical";
}

function challengeTone(status: string): StatusTone {
  if (status === "CONSUMED" || status === "VERIFIED") return "positive";
  if (status === "ISSUED") return "attention";
  if (status === "EXPIRED") return "critical";
  return "neutral";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "empty" | "degraded" | "unavailable";
  title: string;
  description: string;
} {
  if (error instanceof RecoveryDetailBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof RecoveryDetailBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Recovery access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof RecoveryDetailBrowserError && error.status === 404) {
    return {
      kind: "empty",
      title: "Recovery investigation was not found",
      description: "The selected operational reference no longer resolves to an authorized record.",
    };
  }
  if (error instanceof RecoveryDetailBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "Recovery reference is invalid",
      description: "The investigation was not sent because its opaque reference was invalid.",
    };
  }
  return {
    kind: "unavailable",
    title: "Recovery investigation is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

export function RecoveryDetailPanel({
  recoveryReference,
  onClose,
}: {
  recoveryReference: string;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<RecoveryInvestigationDetail>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const requestSequence = useRef(0);

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    try {
      const result = await investigateRecoveryThroughBff(recoveryReference);
      if (sequence === requestSequence.current) setDetail(result);
    } catch (investigationError) {
      if (sequence === requestSequence.current) {
        setError(investigationError);
        setDetail(undefined);
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [recoveryReference]);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  const failure = error ? failureState(error) : undefined;

  return (
    <Panel className="investigationDetailPanel">
      <SectionHeader
        eyebrow="Read-only recovery triage"
        title="Recovery detail"
        description="No Approve, Reject, Retry Challenge, or Complete control is available on this read-only view. Evidence is masked, UTC-ordered, and never added to the page URL."
        trailing={
          <button className="button button--secondary" onClick={onClose} type="button">
            Close investigation
          </button>
        }
      />

      <div aria-live="polite" aria-relevant="additions text">
        {loading && !detail ? (
          <ApplicationState
            kind="loading"
            title="Loading recovery evidence"
            description="The authorized recovery projection is being retrieved."
          />
        ) : failure ? (
          <ApplicationState
            kind={failure.kind}
            title={failure.title}
            description={failure.description}
            action={
              <button className="actionLink" onClick={() => void load()} type="button">
                Retry investigation
              </button>
            }
          />
        ) : detail ? (
          <div className="investigationDetailContent">
            {detail.partial ? (
              <SafeAlert title="Partial or unavailable evidence" tone="attention">
                Unavailable evidence is identified explicitly. It is not interpreted as zero risk,
                successful processing, or confirmed absence.
              </SafeAlert>
            ) : null}

            <div className="investigationSummary" aria-label="Recovery summary">
              <div>
                <span className="investigationLabel">Status</span>
                <StatusBadge
                  label={detail.recovery.status.replaceAll("_", " ")}
                  tone={statusTone(detail.recovery.status)}
                />
              </div>
              <div>
                <span className="investigationLabel">Classification</span>
                <StatusBadge label={detail.recovery.classification.replaceAll("_", " ")} tone="info" />
                <span> · rule {detail.recovery.classificationRuleVersion}</span>
              </div>
              <div>
                <span className="investigationLabel">Risk</span>
                <strong>{detail.recovery.riskScore}</strong>
              </div>
              <div>
                <span className="investigationLabel">Initiated at</span>
                <Timestamp label="Recovery initiated time" value={detail.recovery.initiatedAt} />
              </div>
              <div>
                <span className="investigationLabel">Updated at</span>
                <Timestamp label="Recovery updated time" value={detail.recovery.updatedAt} />
              </div>
              {detail.recovery.eligibleAfter ? (
                <div>
                  <span className="investigationLabel">Eligible after</span>
                  <Timestamp label="Recovery eligible time" value={detail.recovery.eligibleAfter} />
                </div>
              ) : null}
              <div>
                <span className="investigationLabel">Subject</span>
                <MaskedIdentifier
                  label="Masked subject reference"
                  maskedValue={detail.recovery.maskedSubjectReference}
                />
              </div>
              <div>
                <span className="investigationLabel">Originating decision</span>
                <MaskedIdentifier
                  label="Masked originating decision reference"
                  maskedValue={detail.recovery.originatingDecisionReference}
                />
              </div>
              <div>
                <span className="investigationLabel">Protection request</span>
                <MaskedIdentifier
                  label="Masked protection request reference"
                  maskedValue={detail.protectionRequestReference}
                />
              </div>
              <div>
                <span className="investigationLabel">Review</span>
                <StatusBadge
                  label={detail.recovery.reviewState.replaceAll("_", " ")}
                  tone={detail.recovery.reviewState === "PENDING" ? "attention" : "muted"}
                />
                {detail.reviewerPresent ? (
                  <StatusBadge label="reviewer recorded" tone="positive" />
                ) : null}
              </div>
            </div>

            <section className="investigationSection" aria-labelledby="challenge-evidence-heading">
              <div className="investigationSectionHeader">
                <h3 id="challenge-evidence-heading">Identity challenge evidence</h3>
                <StatusBadge
                  label={detail.challengeAvailability.replaceAll("_", " ")}
                  tone={availabilityTone(detail.challengeAvailability)}
                />
              </div>
              {detail.challenges.length > 0 ? (
                detail.challenges.map((item) => (
                  <div className="investigationRecord" key={item.reference}>
                    <div>
                      <strong>{item.challengeType.replaceAll("_", " ")}</strong>
                      <span>{item.purpose.replaceAll("_", " ")}</span>
                    </div>
                    <StatusBadge label={item.status} tone={challengeTone(item.status)} />
                    <Timestamp label="Challenge created time" value={item.createdAt} />
                  </div>
                ))
              ) : (
                <p className="muted">
                  {detail.challengeAvailability === "NOT_APPLICABLE"
                    ? "No identity challenge is expected for this recovery."
                    : "Identity challenge evidence is unavailable. This is not confirmed absence."}
                </p>
              )}
            </section>

            <section className="investigationSection" aria-labelledby="recovery-next-steps-heading">
              <div className="investigationSectionHeader">
                <h3 id="recovery-next-steps-heading">Next steps</h3>
                <StatusBadge label={`source ${detail.source}`} tone="info" />
              </div>
              <p className="muted">
                This view is read-only. Reviewing, approving, rejecting, or completing this recovery
                requires the authenticated operator session and maker-checker workflow planned by a
                future issue.
              </p>
            </section>
          </div>
        ) : null}
      </div>
    </Panel>
  );
}
