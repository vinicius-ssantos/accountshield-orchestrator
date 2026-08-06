"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  ApplicationState,
  Panel,
  SectionHeader,
  StatusBadge,
  type StatusTone,
} from "@/design-system/components";

import {
  DecisionReplayBrowserError,
  replayDecisionThroughBff,
} from "./decision-replay-browser";
import type { DecisionReplayComparison, DecisionReplaySide } from "./types";

function outcomeTone(outcome: string): StatusTone {
  if (outcome === "ALLOW") return "positive";
  if (outcome === "REQUIRE_STEP_UP") return "attention";
  return "critical";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "empty" | "degraded" | "unavailable";
  title: string;
  description: string;
} {
  if (error instanceof DecisionReplayBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof DecisionReplayBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Decision replay access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof DecisionReplayBrowserError && error.status === 404) {
    return {
      kind: "empty",
      title: "Decision replay was not found",
      description: "The selected operational reference no longer resolves to an authorized record.",
    };
  }
  if (error instanceof DecisionReplayBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "Decision reference is invalid",
      description: "The replay was not sent because its opaque reference was invalid.",
    };
  }
  if (error instanceof DecisionReplayBrowserError && error.status === 503) {
    return {
      kind: "unavailable",
      title: "The historical version could not be resolved",
      description:
        "The algorithm or policy version this decision used is no longer available for replay. " +
        "No sensitive diagnostic detail was exposed.",
    };
  }
  return {
    kind: "unavailable",
    title: "Decision replay is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

function FieldComparison({
  label,
  original,
  replayed,
}: {
  label: string;
  original: string | number;
  replayed: string | number;
}) {
  const diverged = String(original) !== String(replayed);
  return (
    <tr className={diverged ? "replayComparisonRowDiverged" : undefined}>
      <th scope="row">{label}</th>
      <td>{original}</td>
      <td>{replayed}</td>
      <td>
        <StatusBadge label={diverged ? "diverged" : "match"} tone={diverged ? "critical" : "positive"} />
      </td>
    </tr>
  );
}

function reasonsLabel(side: DecisionReplaySide): string {
  if (side.reasons.length === 0) return "none";
  return side.reasons
    .map((reason) => `${reason.code.replaceAll("_", " ")} (${reason.contribution > 0 ? "+" : ""}${reason.contribution})`)
    .join(", ");
}

export function DecisionReplayPanel({
  decisionReference,
  onClose,
}: {
  decisionReference: string;
  onClose: () => void;
}) {
  const [comparison, setComparison] = useState<DecisionReplayComparison>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const requestSequence = useRef(0);

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    try {
      const result = await replayDecisionThroughBff(decisionReference);
      if (sequence === requestSequence.current) setComparison(result);
    } catch (replayError) {
      if (sequence === requestSequence.current) {
        setError(replayError);
        setComparison(undefined);
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
        eyebrow="Side-effect-free deterministic replay"
        title="Replay comparison"
        description="Historical evidence is re-evaluated read-only against the exact recorded algorithm and policy version. No challenge, recovery, outbox, or audit record is created by this view."
        trailing={
          <button className="button button--secondary" onClick={onClose} type="button">
            Close replay
          </button>
        }
      />

      <div aria-live="polite" aria-relevant="additions text">
        {loading && !comparison ? (
          <ApplicationState
            kind="loading"
            title="Replaying decision"
            description="The deterministic replay engine is re-evaluating the historical input."
          />
        ) : failure ? (
          <ApplicationState
            kind={failure.kind}
            title={failure.title}
            description={failure.description}
            action={
              <button className="actionLink" onClick={() => void load()} type="button">
                Retry replay
              </button>
            }
          />
        ) : comparison ? (
          <div className="investigationDetailContent">
            <div className="investigationSummary" aria-label="Replay overall status">
              <div>
                <span className="investigationLabel">Overall</span>
                <StatusBadge
                  label={comparison.matches ? "exact match" : "diverged"}
                  tone={comparison.matches ? "positive" : "critical"}
                />
              </div>
              <div>
                <span className="investigationLabel">Original outcome</span>
                <StatusBadge
                  label={comparison.original.outcome.replaceAll("_", " ")}
                  tone={outcomeTone(comparison.original.outcome)}
                />
              </div>
              <div>
                <span className="investigationLabel">Replayed outcome</span>
                <StatusBadge
                  label={comparison.replayed.outcome.replaceAll("_", " ")}
                  tone={outcomeTone(comparison.replayed.outcome)}
                />
              </div>
            </div>

            <section className="investigationSection" aria-labelledby="replay-comparison-heading">
              <div className="investigationSectionHeader">
                <h3 id="replay-comparison-heading">Field-level comparison</h3>
                <StatusBadge label={`source ${comparison.source}`} tone="info" />
              </div>
              <table className="replayComparisonTable">
                <caption className="srOnly">Original versus replayed decision fields</caption>
                <thead>
                  <tr>
                    <th scope="col">Field</th>
                    <th scope="col">Original</th>
                    <th scope="col">Replayed</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  <FieldComparison
                    label="Outcome"
                    original={comparison.original.outcome}
                    replayed={comparison.replayed.outcome}
                  />
                  <FieldComparison
                    label="Risk score"
                    original={comparison.original.riskScore}
                    replayed={comparison.replayed.riskScore}
                  />
                  <FieldComparison
                    label="Risk band"
                    original={comparison.original.riskBand}
                    replayed={comparison.replayed.riskBand}
                  />
                  <FieldComparison
                    label="Reasons"
                    original={reasonsLabel(comparison.original)}
                    replayed={reasonsLabel(comparison.replayed)}
                  />
                </tbody>
              </table>
            </section>

            {comparison.mismatches.length > 0 ? (
              <section className="investigationSection" aria-labelledby="replay-mismatches-heading">
                <div className="investigationSectionHeader">
                  <h3 id="replay-mismatches-heading">Divergence detail</h3>
                  <StatusBadge
                    label={`${comparison.mismatches.length} mismatch${comparison.mismatches.length === 1 ? "" : "es"}`}
                    tone="critical"
                  />
                </div>
                <ul className="replayMismatchList">
                  {comparison.mismatches.map((mismatch) => (
                    <li key={mismatch}>{mismatch}</li>
                  ))}
                </ul>
              </section>
            ) : null}

            <section className="investigationSection" aria-labelledby="replay-provenance-heading">
              <div className="investigationSectionHeader">
                <h3 id="replay-provenance-heading">Execution provenance used for both sides</h3>
              </div>
              <dl className="investigationEvidenceGrid">
                <div className="investigationEvidenceValue">
                  <dt>Policy</dt>
                  <dd>{comparison.policyKey} · {comparison.policyVersion}</dd>
                </div>
                <div className="investigationEvidenceValue">
                  <dt>Algorithm</dt>
                  <dd>{comparison.algorithmVersion}</dd>
                </div>
                <div className="investigationEvidenceValue">
                  <dt>Input schema</dt>
                  <dd>{comparison.normalizedInputSchemaVersion ?? "Unavailable"}</dd>
                </div>
                <div className="investigationEvidenceValue">
                  <dt>Reason catalog</dt>
                  <dd>{comparison.reasonCatalogVersion}</dd>
                </div>
                <div className="investigationEvidenceValue">
                  <dt>Decision engine</dt>
                  <dd>{comparison.decisionEngineVersion}</dd>
                </div>
              </dl>
              <p className="muted">
                Recovery-classification divergence is not compared by this view; the replay engine does
                not compute it.
              </p>
            </section>
          </div>
        ) : null}
      </div>
    </Panel>
  );
}
