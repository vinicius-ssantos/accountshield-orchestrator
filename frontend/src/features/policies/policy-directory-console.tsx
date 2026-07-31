"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  ApplicationState,
  DataTable,
  Panel,
  SectionHeader,
  StatusBadge,
  Timestamp,
  type DataTableRow,
} from "@/design-system/components";

import { PolicyInvestigationPanel } from "./policy-investigation-panel";
import {
  PolicyDirectoryBrowserError,
  searchPoliciesThroughBff,
} from "./policy-directory-browser";
import type { PolicyDirectoryPage } from "./types";

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "unavailable";
  title: string;
  description: string;
} {
  if (error instanceof PolicyDirectoryBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof PolicyDirectoryBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Policy directory access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  return {
    kind: "unavailable",
    title: "Policy directory is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

export function PolicyDirectoryConsole() {
  const [result, setResult] = useState<PolicyDirectoryPage>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [selectedPolicyKey, setSelectedPolicyKey] = useState<string>();
  const requestSequence = useRef(0);

  const runSearch = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    try {
      const page = await searchPoliciesThroughBff();
      if (sequence === requestSequence.current) setResult(page);
    } catch (searchError) {
      if (sequence === requestSequence.current) {
        setError(searchError);
        setResult(undefined);
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => void runSearch());
  }, [runSearch]);

  const rows: readonly DataTableRow[] = (result?.policies ?? []).map((policy) => ({
    id: policy.policyKey,
    cells: {
      policyKey: policy.policyKey,
      totalVersions: policy.totalVersions,
      activeVersion: policy.activeVersion ? (
        <span className="decisionFlags">
          <StatusBadge label={policy.activeVersion} tone="positive" />
          {policy.activeVersionActivatedAt ? (
            <Timestamp label="Active since" value={policy.activeVersionActivatedAt} />
          ) : null}
        </span>
      ) : (
        <StatusBadge label="no active version" tone="muted" />
      ),
      rollout: policy.hasActiveRollout ? (
        <StatusBadge label="canary in progress" tone="attention" />
      ) : (
        <StatusBadge label="no rollout" tone="muted" />
      ),
      action: (
        <button
          aria-pressed={selectedPolicyKey === policy.policyKey}
          className="actionLink decisionInvestigateAction"
          onClick={() => setSelectedPolicyKey(policy.policyKey)}
          type="button"
        >
          Investigate policy
        </button>
      ),
    },
  }));

  const failure = error ? failureState(error) : undefined;

  return (
    <>
      <Panel>
        <SectionHeader
          eyebrow="Authorized read surface"
          title="Policy directory"
          description="Lifecycle, rollout, and impact-analysis visibility only. No approve, activate, rollback, retire, or rollout-percentage control is exposed here."
          trailing={
            <button
              className="button button--secondary"
              disabled={loading}
              onClick={() => void runSearch()}
              type="button"
            >
              {loading ? "Refreshing…" : "Refresh"}
            </button>
          }
        />
      </Panel>

      <div aria-live="polite" aria-relevant="additions text">
        {loading && !result ? (
          <ApplicationState
            kind="loading"
            title="Loading policy directory"
            description="The privacy-minimized read model is being queried."
          />
        ) : failure ? (
          <ApplicationState
            kind={failure.kind}
            title={failure.title}
            description={failure.description}
            action={
              <button className="actionLink" onClick={() => void runSearch()} type="button">
                Retry
              </button>
            }
          />
        ) : result && result.policies.length === 0 ? (
          <ApplicationState
            kind="empty"
            title="No policies found"
            description="The privacy-minimized read model returned no policy keys."
          />
        ) : result ? (
          <Panel>
            <SectionHeader
              eyebrow="Policy keys"
              title={`${result.policies.length} polic${result.policies.length === 1 ? "y" : "ies"}`}
              description={`Source: ${result.source}.`}
            />
            <DataTable
              caption="Policy directory results"
              columns={[
                { key: "policyKey", label: "Policy key" },
                { key: "totalVersions", label: "Versions", align: "end" },
                { key: "activeVersion", label: "Active version" },
                { key: "rollout", label: "Rollout" },
                { key: "action", label: "Investigation" },
              ]}
              rows={rows}
            />
          </Panel>
        ) : null}
      </div>

      {selectedPolicyKey ? (
        <PolicyInvestigationPanel
          onClose={() => setSelectedPolicyKey(undefined)}
          policyKey={selectedPolicyKey}
        />
      ) : null}
    </>
  );
}
