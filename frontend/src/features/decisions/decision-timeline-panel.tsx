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
  maskIdentifier,
  type StatusTone,
} from "@/design-system/components";

import {
  DecisionTimelineBrowserError,
  investigateDecisionThroughBff,
} from "./decision-timeline-browser";
import type {
  DecisionInvestigationDetail,
  SectionAvailability,
  SignalProvenanceState,
} from "./types";

function outcomeTone(outcome: string): StatusTone {
  if (outcome === "ALLOW") return "positive";
  if (outcome === "REQUIRE_STEP_UP") return "attention";
  return "critical";
}

function signalTone(state: SignalProvenanceState): StatusTone {
  if (state === "RECORDED") return "positive";
  if (state === "SIMULATED") return "info";
  if (state === "STALE") return "attention";
  return "critical";
}

function availabilityTone(value: SectionAvailability): StatusTone {
  if (value === "AVAILABLE") return "positive";
  if (value === "NOT_APPLICABLE") return "muted";
  return "critical";
}

function integrityLabel(value: boolean): string {
  return value ? "recorded" : "unavailable";
}

function integrityTone(value: boolean): StatusTone {
  return value ? "positive" : "critical";
}

function timelineTone(status: string): StatusTone {
  if (status === "PUBLISHED" || status === "CONSUMED" || status === "ALLOW") {
    return "positive";
  }
  if (status === "ISSUED" || status === "AUTHORIZED" || status === "REQUIRE_STEP_UP") {
    return "attention";
  }
  if (status === "UNAVAILABLE" || status === "DEAD_LETTERED") return "critical";
  return "neutral";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "empty" | "degraded" | "unavailable";
  title: string;
  description: string;
} {
  if (error instanceof DecisionTimelineBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof DecisionTimelineBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Decision access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof DecisionTimelineBrowserError && error.status === 404) {
    return {
      kind: "empty",
      title: "Decision investigation was not found",
      description: "The selected operational reference no longer resolves to an authorized record.",
    };
  }
  if (error instanceof DecisionTimelineBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "Decision reference is invalid",
      description: "The investigation was not sent because its opaque reference was invalid.",
    };
  }
  return {
    kind: "unavailable",
    title: "Decision investigation is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

function EvidenceValue({
  label,
  value,
}: {
  label: string;
  value: string | number | null;
}) {
  return (
    <div className="investigationEvidenceValue">
      <dt>{label}</dt>
      <dd>{value === null || value === "" ? "Unavailable" : value}</dd>
    </div>
  );
}

function AvailabilitySummary({
  label,
  value,
}: {
  label: string;
  value: SectionAvailability;
}) {
  return (
    <div className="investigationAvailability">
      <span>{label}</span>
      <StatusBadge label={value.replaceAll("_", " ")} tone={availabilityTone(value)} />
    </div>
  );
}

export function DecisionTimelinePanel({
  decisionReference,
  onClose,
}: {
  decisionReference: string;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<DecisionInvestigationDetail>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const requestSequence = useRef(0);

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    try {
      const result = await investigateDecisionThroughBff(decisionReference);
      if (sequence === requestSequence.current) setDetail(result);
    } catch (investigationError) {
      if (sequence === requestSequence.current) {
        setError(investigationError);
        setDetail(undefined);
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [decisionReference]);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  const failure = error ? failureState(error) : undefined;

  return (
    <Panel className="investigationDetailPanel">
      <SectionHeader
        eyebrow="Explainable decision journey"
        title="Decision explanation"
        description="Historical evidence is read-only, privacy-minimized, ordered in UTC, and never added to the page URL."
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
            title="Loading decision evidence"
            description="The authorized timeline projection is being retrieved."
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
            {detail.partial || detail.decision.degraded ? (
              <SafeAlert title="Partial or degraded evidence" tone="attention">
                Unavailable evidence is identified explicitly. It is not interpreted as zero risk,
                successful processing, or confirmed absence.
              </SafeAlert>
            ) : null}

            <div className="investigationSummary" aria-label="Decision summary">
              <div>
                <span className="investigationLabel">Outcome</span>
                <StatusBadge
                  label={detail.decision.outcome.replaceAll("_", " ")}
                  tone={outcomeTone(detail.decision.outcome)}
                />
              </div>
              <div>
                <span className="investigationLabel">Risk</span>
                <strong>{detail.decision.riskScore}</strong>{" "}
                <StatusBadge
                  label={detail.decision.riskBand}
                  tone={
                    detail.decision.riskBand === "HIGH"
                      ? "critical"
                      : detail.decision.riskBand === "MEDIUM"
                        ? "attention"
                        : "positive"
                  }
                />
              </div>
              <div>
                <span className="investigationLabel">Decided at</span>
                <Timestamp label="Decision time" value={detail.decision.decidedAt} />
              </div>
              <div>
                <span className="investigationLabel">Subject</span>
                <MaskedIdentifier
                  label="Masked subject reference"
                  maskedValue={detail.maskedSubjectReference}
                />
              </div>
              <div>
                <span className="investigationLabel">Correlation</span>
                <MaskedIdentifier
                  label="Masked correlation ID"
                  maskedValue={maskIdentifier(detail.decision.correlationId, 6, 4)}
                />
              </div>
              <div>
                <span className="investigationLabel">Decision reference</span>
                <MaskedIdentifier
                  label="Masked decision reference"
                  maskedValue={maskIdentifier(detail.decision.decisionReference, 4, 4)}
                />
              </div>
            </div>

            <section className="investigationSection" aria-labelledby="risk-evidence-heading">
              <div className="investigationSectionHeader">
                <h3 id="risk-evidence-heading">Risk reasons</h3>
                <StatusBadge
                  label={`${detail.reasons.length} ordered reason${detail.reasons.length === 1 ? "" : "s"}`}
                  tone="info"
                />
              </div>
              <ol className="reasonEvidenceList">
                {detail.reasons.map((reason) => (
                  <li key={`${reason.ordinal}-${reason.code}`}>
                    <span>{reason.code.replaceAll("_", " ")}</span>
                    <strong>
                      {reason.contribution > 0 ? "+" : ""}
                      {reason.contribution}
                    </strong>
                  </li>
                ))}
              </ol>
            </section>

            <div className="investigationProvenanceGrid">
              <section className="investigationSection" aria-labelledby="signal-provenance-heading">
                <div className="investigationSectionHeader">
                  <h3 id="signal-provenance-heading">Signal provenance</h3>
                  <StatusBadge
                    label={detail.signalProvenance.state}
                    tone={signalTone(detail.signalProvenance.state)}
                  />
                </div>
                <dl className="investigationEvidenceGrid">
                  <EvidenceValue label="Provider" value={detail.signalProvenance.provider} />
                  <EvidenceValue label="Confidence" value={detail.signalProvenance.confidence} />
                  <EvidenceValue label="Schema" value={detail.signalProvenance.schemaVersion} />
                  <div className="investigationEvidenceValue">
                    <dt>Observed at</dt>
                    <dd>
                      {detail.signalProvenance.observedAt ? (
                        <Timestamp
                          label="Signal observed time"
                          value={detail.signalProvenance.observedAt}
                        />
                      ) : (
                        "Unavailable"
                      )}
                    </dd>
                  </div>
                </dl>
                <div className="investigationFlags">
                  <StatusBadge
                    label={detail.signalProvenance.simulated ? "simulated input" : "recorded input"}
                    tone={detail.signalProvenance.simulated ? "info" : "positive"}
                  />
                  <StatusBadge
                    label={`integrity ${integrityLabel(detail.signalProvenance.integrityAvailable)}`}
                    tone={integrityTone(detail.signalProvenance.integrityAvailable)}
                  />
                </div>
              </section>

              <section className="investigationSection" aria-labelledby="policy-provenance-heading">
                <div className="investigationSectionHeader">
                  <h3 id="policy-provenance-heading">Policy provenance</h3>
                  <StatusBadge
                    label={detail.policyProvenance.routingReason.replaceAll("_", " ")}
                    tone={detail.policyProvenance.routingReason === "UNAVAILABLE" ? "critical" : "positive"}
                  />
                </div>
                <dl className="investigationEvidenceGrid">
                  <EvidenceValue label="Policy key" value={detail.policyProvenance.policyKey} />
                  <EvidenceValue label="Exact version" value={detail.policyProvenance.policyVersion} />
                  <EvidenceValue
                    label="Cohort bucket"
                    value={detail.policyProvenance.rolloutCohortBucket}
                  />
                  <EvidenceValue
                    label="Candidate version"
                    value={detail.policyProvenance.rolloutCandidateVersion}
                  />
                </dl>
              </section>

              <section className="investigationSection" aria-labelledby="execution-provenance-heading">
                <div className="investigationSectionHeader">
                  <h3 id="execution-provenance-heading">Execution provenance</h3>
                  <StatusBadge
                    label={
                      detail.executionProvenance.auditRecordHashAvailable
                        ? "audit integrity recorded"
                        : "audit integrity unavailable"
                    }
                    tone={integrityTone(detail.executionProvenance.auditRecordHashAvailable)}
                  />
                </div>
                <dl className="investigationEvidenceGrid">
                  <EvidenceValue
                    label="Algorithm"
                    value={detail.executionProvenance.algorithmVersion}
                  />
                  <EvidenceValue
                    label="Decision engine"
                    value={detail.executionProvenance.decisionEngineVersion}
                  />
                  <EvidenceValue
                    label="Input schema"
                    value={detail.executionProvenance.normalizedInputSchemaVersion}
                  />
                  <EvidenceValue
                    label="Reason catalog"
                    value={detail.executionProvenance.reasonCatalogVersion}
                  />
                  <EvidenceValue
                    label="Application commit"
                    value={detail.executionProvenance.applicationCommitSha}
                  />
                </dl>
                <StatusBadge
                  label={
                    detail.executionProvenance.canonicalInputHashAvailable
                      ? "canonical input hash recorded"
                      : "canonical input hash unavailable"
                  }
                  tone={integrityTone(
                    detail.executionProvenance.canonicalInputHashAvailable,
                  )}
                />
              </section>
            </div>

            <section className="investigationSection" aria-labelledby="downstream-heading">
              <div className="investigationSectionHeader">
                <h3 id="downstream-heading">Downstream evidence</h3>
                <StatusBadge label={`source ${detail.source}`} tone="info" />
              </div>
              <div className="investigationAvailabilityGrid">
                <AvailabilitySummary label="Challenge" value={detail.sections.challenge} />
                <AvailabilitySummary label="Recovery" value={detail.sections.recovery} />
                <AvailabilitySummary label="Outbox" value={detail.sections.outbox} />
              </div>
              {detail.challenges.map((item) => (
                <div className="investigationRecord" key={item.reference}>
                  <div>
                    <strong>{item.challengeType.replaceAll("_", " ")}</strong>
                    <span>{item.purpose.replaceAll("_", " ")}</span>
                  </div>
                  <StatusBadge label={item.status} tone={timelineTone(item.status)} />
                  <Timestamp label="Challenge created time" value={item.createdAt} />
                </div>
              ))}
              {detail.recovery ? (
                <div className="investigationRecord">
                  <div>
                    <strong>{detail.recovery.directive.replaceAll("_", " ")}</strong>
                    <span>Recovery authorization</span>
                  </div>
                  <StatusBadge
                    label={detail.recovery.status}
                    tone={timelineTone(detail.recovery.status)}
                  />
                  <Timestamp label="Recovery issued time" value={detail.recovery.issuedAt} />
                </div>
              ) : null}
              {detail.outboxEvents.map((item) => (
                <div className="investigationRecord" key={item.reference}>
                  <div>
                    <strong>{item.eventType}</strong>
                    <span>{item.attemptCount} publication attempt(s)</span>
                  </div>
                  <StatusBadge label={item.status} tone={timelineTone(item.status)} />
                  <Timestamp label="Outbox event time" value={item.occurredAt} />
                </div>
              ))}
            </section>

            <section className="investigationSection" aria-labelledby="timeline-heading">
              <div className="investigationSectionHeader">
                <h3 id="timeline-heading">Ordered event timeline</h3>
                <StatusBadge label="UTC · stable ordering" tone="info" />
              </div>
              <ol aria-label="Decision event timeline" className="investigationTimeline">
                {detail.timeline.map((item, index) => (
                  <li className="investigationTimelineItem" key={`${item.kind}-${item.reference}`}>
                    <span aria-hidden="true" className="investigationTimelineMarker">
                      {index + 1}
                    </span>
                    <div>
                      <div className="investigationTimelineHeader">
                        <strong>{item.kind.replaceAll("_", " ")}</strong>
                        <StatusBadge label={item.status} tone={timelineTone(item.status)} />
                      </div>
                      <MaskedIdentifier
                        label="Masked event reference"
                        maskedValue={maskIdentifier(item.reference, 4, 4)}
                      />
                      <Timestamp label={`${item.kind} event time`} value={item.occurredAt} />
                    </div>
                  </li>
                ))}
              </ol>
            </section>
          </div>
        ) : null}
      </div>
    </Panel>
  );
}
