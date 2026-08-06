"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  ApplicationState,
  DataTable,
  Panel,
  SafeAlert,
  SectionHeader,
  StatusBadge,
  Timestamp,
  type DataTableRow,
  type StatusTone,
} from "@/design-system/components";

import {
  PolicyInvestigationBrowserError,
  investigatePolicyThroughBff,
} from "./policy-investigation-browser";
import { PolicyLifecycleActions } from "./policy-lifecycle-actions";
import { PolicyRolloutActions } from "./policy-rollout-actions";
import type {
  PolicyImpactAvailability,
  PolicyInvestigationDetail,
  PolicyLifecycleStatus,
  PolicySegmentImpact,
} from "./types";

function statusTone(status: PolicyLifecycleStatus): StatusTone {
  if (status === "ACTIVE") return "positive";
  if (status === "REJECTED") return "critical";
  if (status === "RETIRED") return "muted";
  if (status === "APPROVED") return "info";
  return "attention";
}

function availabilityTone(value: PolicyImpactAvailability): StatusTone {
  if (value === "AVAILABLE") return "positive";
  if (value === "NOT_APPLICABLE") return "muted";
  return "critical";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "empty" | "degraded" | "unavailable";
  title: string;
  description: string;
} {
  if (error instanceof PolicyInvestigationBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof PolicyInvestigationBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Policy investigation access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof PolicyInvestigationBrowserError && error.status === 404) {
    return {
      kind: "empty",
      title: "Policy investigation was not found",
      description: "The selected policy key no longer resolves to an authorized record.",
    };
  }
  if (error instanceof PolicyInvestigationBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "Policy key is invalid",
      description: "The investigation was not sent because the policy key was invalid.",
    };
  }
  return {
    kind: "unavailable",
    title: "Policy investigation is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

function SegmentTable({
  caption,
  segments,
}: {
  caption: string;
  segments: Readonly<Record<string, PolicySegmentImpact>>;
}) {
  const rows: readonly DataTableRow[] = Object.values(segments).map((segment) => ({
    id: segment.segment,
    cells: {
      segment: segment.segment.replaceAll("_", " "),
      total: segment.totalDecisions,
      divergent: segment.divergentDecisions,
      percentage: `${segment.totalDecisions === 0 ? 0 : ((segment.divergentDecisions * 100) / segment.totalDecisions).toFixed(1)}%`,
    },
  }));

  return (
    <DataTable
      caption={caption}
      columns={[
        { key: "segment", label: "Segment" },
        { key: "total", label: "Total", align: "end" },
        { key: "divergent", label: "Divergent", align: "end" },
        { key: "percentage", label: "Divergence", align: "end" },
      ]}
      rows={rows}
    />
  );
}

export function PolicyInvestigationPanel({
  policyKey,
  currentSubject,
  onClose,
}: {
  policyKey: string;
  currentSubject: string | undefined;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<PolicyInvestigationDetail>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const requestSequence = useRef(0);

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    try {
      const result = await investigatePolicyThroughBff(policyKey);
      if (sequence === requestSequence.current) setDetail(result);
    } catch (investigationError) {
      if (sequence === requestSequence.current) {
        setError(investigationError);
        setDetail(undefined);
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [policyKey]);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  const failure = error ? failureState(error) : undefined;

  const versionRows: readonly DataTableRow[] = (detail?.versions ?? []).map((version) => ({
    id: version.id,
    cells: {
      version: version.version,
      status: <StatusBadge label={version.status} tone={statusTone(version.status)} />,
      thresholds: `${version.allowMaxScore ?? "—"} / ${version.stepUpMaxScore ?? "—"} / ${version.recoveryMaxScore ?? "—"}`,
      created: <Timestamp value={version.createdAt} />,
      activated: version.activatedAt ? <Timestamp value={version.activatedAt} /> : <span className="muted">Not activated</span>,
      governance: version.governance ? (
        <span className="decisionFlags">
          <span>author: {version.governance.createdBy ?? "—"}</span>
          {version.governance.approvedBy ? <span>approver: {version.governance.approvedBy}</span> : null}
        </span>
      ) : (
        <span className="muted">Unavailable</span>
      ),
      diagnostics: version.analysis ? (
        <span className="decisionFlags">
          {version.analysis.diagnostics.length === 0 ? (
            <StatusBadge label="no findings" tone="positive" />
          ) : (
            version.analysis.diagnostics.map((d) => (
              <StatusBadge
                key={d.code}
                label={d.code.replaceAll("_", " ")}
                tone={d.severity === "ERROR" ? "critical" : "attention"}
              />
            ))
          )}
        </span>
      ) : (
        <span className="muted">Not yet analyzed</span>
      ),
    },
  }));

  return (
    <Panel className="investigationDetailPanel">
      <SectionHeader
        eyebrow="Read-only policy lifecycle"
        title="Policy detail"
        description="Approve, activate, reject, retire, and canary rollout controls (start, adjust percentage, roll back) are available for eligible versions below."
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
            title="Loading policy evidence"
            description="The authorized policy projection is being retrieved."
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
            <div className="investigationSummary" aria-label="Policy summary">
              <div>
                <span className="investigationLabel">Policy key</span>
                <strong>{detail.policyKey}</strong>
              </div>
              <div>
                <span className="investigationLabel">Versions</span>
                <strong>{detail.versions.length}</strong>
              </div>
              <div>
                <span className="investigationLabel">Routing scope</span>
                <strong>{detail.routingScope.length} route{detail.routingScope.length === 1 ? "" : "s"}</strong>
              </div>
            </div>

            <section className="investigationSection" aria-labelledby="policy-version-history-heading">
              <div className="investigationSectionHeader">
                <h3 id="policy-version-history-heading">Version history</h3>
                <StatusBadge label={`source ${detail.source}`} tone="info" />
              </div>
              <DataTable
                caption="Policy version history"
                columns={[
                  { key: "version", label: "Version" },
                  { key: "status", label: "Status" },
                  { key: "thresholds", label: "Allow / Step-up / Recovery" },
                  { key: "created", label: "Created" },
                  { key: "activated", label: "Activated" },
                  { key: "governance", label: "Governance" },
                  { key: "diagnostics", label: "Diagnostics" },
                ]}
                rows={versionRows}
              />
              {(detail?.versions ?? []).map((version) => (
                <PolicyLifecycleActions
                  key={version.id}
                  policyKey={detail.policyKey}
                  version={version.version}
                  status={version.status}
                  governance={version.governance}
                  currentSubject={currentSubject}
                  onChanged={() => void load()}
                />
              ))}
            </section>

            {detail.routingScope.length > 0 ? (
              <section className="investigationSection" aria-labelledby="policy-routing-scope-heading">
                <div className="investigationSectionHeader">
                  <h3 id="policy-routing-scope-heading">Routing scope</h3>
                </div>
                <ul className="reasonEvidenceList">
                  {detail.routingScope.map((entry) => (
                    <li key={`${entry.clientId}-${entry.eventType}`}>
                      <span>{entry.clientId}</span>
                      <span>{entry.eventType.replaceAll("_", " ")}</span>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}

            <section className="investigationSection" aria-labelledby="policy-rollout-heading">
              <div className="investigationSectionHeader">
                <h3 id="policy-rollout-heading">Active rollout</h3>
              </div>
              {detail.activeRollout ? (
                <div className="investigationSummary" aria-label="Active rollout summary">
                  <div>
                    <span className="investigationLabel">Candidate</span>
                    <strong>{detail.activeRollout.candidateVersion}</strong>
                  </div>
                  <div>
                    <span className="investigationLabel">Percentage</span>
                    <strong>{detail.activeRollout.rolloutPercentage}%</strong>
                  </div>
                  <div>
                    <span className="investigationLabel">Started</span>
                    <Timestamp value={detail.activeRollout.startedAt} />
                  </div>
                </div>
              ) : (
                <p className="muted">
                  No canary rollout is currently in progress for this policy. This read model shows
                  only the active rollout, not rollout history — a previously rolled-back canary is
                  not distinguishable here from a policy that never had one.
                </p>
              )}
              <PolicyRolloutActions
                policyKey={detail.policyKey}
                approvedCandidateVersions={detail.versions
                  .filter((version) => version.status === "APPROVED")
                  .map((version) => version.version)}
                activeRollout={detail.activeRollout}
                onChanged={() => void load()}
              />
            </section>

            <section className="investigationSection" aria-labelledby="policy-impact-heading">
              <div className="investigationSectionHeader">
                <h3 id="policy-impact-heading">Historical impact analysis</h3>
                <StatusBadge
                  label={detail.impactAvailability.replaceAll("_", " ")}
                  tone={availabilityTone(detail.impactAvailability)}
                />
              </div>

              {detail.impactAvailability === "NOT_APPLICABLE" ? (
                <p className="muted">No active rollout, so there is no candidate to compare against.</p>
              ) : detail.impactAvailability === "UNAVAILABLE" ? (
                <SafeAlert title="Impact analysis is unavailable" tone="attention">
                  An active rollout exists but its impact analysis could not be produced. This is not
                  the same as zero divergence.
                </SafeAlert>
              ) : detail.impactAnalysis ? (
                <>
                  <div className="investigationSummary" aria-label="Impact analysis summary">
                    <div>
                      <span className="investigationLabel">Total decisions sampled</span>
                      <strong>{detail.impactAnalysis.totalDecisions}</strong>
                    </div>
                    <div>
                      <span className="investigationLabel">Divergent</span>
                      <strong>{detail.impactAnalysis.divergentDecisionsCount}</strong>
                      {" "}
                      ({detail.impactAnalysis.divergencePercentage.toFixed(1)}%)
                    </div>
                    <div>
                      <span className="investigationLabel">Threshold</span>
                      <StatusBadge
                        label={
                          detail.impactAnalysis.exceedsDivergenceThreshold
                            ? `exceeds ${detail.impactAnalysis.maxDivergencePercentageThreshold}%`
                            : `within ${detail.impactAnalysis.maxDivergencePercentageThreshold}%`
                        }
                        tone={detail.impactAnalysis.exceedsDivergenceThreshold ? "critical" : "positive"}
                      />
                    </div>
                  </div>

                  <SegmentTable caption="Impact by event type" segments={detail.impactAnalysis.impactByEventType} />
                  <SegmentTable caption="Impact by risk band" segments={detail.impactAnalysis.impactByRiskBand} />

                  {detail.impactAnalysis.divergentDecisions.length > 0 ? (
                    <ul className="reasonEvidenceList" aria-label="Representative divergent decisions">
                      {detail.impactAnalysis.divergentDecisions.map((decision) => (
                        <li key={decision.maskedProtectionRequestReference}>
                          <span>
                            {decision.originalOutcome.replaceAll("_", " ")} → {decision.candidateOutcome.replaceAll("_", " ")}
                            {" "}(risk {decision.riskScore})
                          </span>
                          <span className="muted">{decision.maskedProtectionRequestReference}</span>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </>
              ) : null}
            </section>
          </div>
        ) : null}
      </div>
    </Panel>
  );
}
