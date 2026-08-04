"use client";

import { useState } from "react";

import { Panel, SafeAlert, SectionHeader } from "@/design-system/components";

import {
  EvidenceExportBrowserError,
  exportEvidenceThroughBff,
  verifyEvidenceBundleThroughBff,
} from "./evidence-export-browser";
import type { EvidenceBundle, EvidenceVerificationResult } from "./types";

type Stage =
  | { name: "reason-input" }
  | { name: "exporting" }
  | { name: "exported"; bundle: EvidenceBundle }
  | { name: "verifying"; bundle: EvidenceBundle }
  | { name: "verified"; bundle: EvidenceBundle; result: EvidenceVerificationResult }
  | { name: "error"; message: string; retryable: boolean };

function errorMessage(error: unknown): { message: string; retryable: boolean } {
  if (error instanceof EvidenceExportBrowserError) {
    if (error.status === 401) {
      return {
        message: "Your operator session is no longer valid. Sign in again to continue.",
        retryable: false,
      };
    }
    if (error.status === 403) {
      return { message: "Evidence export is not permitted for the authenticated operator.", retryable: false };
    }
    if (error.status === 404) {
      return { message: "This decision's protection request could not be found.", retryable: false };
    }
    if (error.status === 400) {
      return { message: "The export reason is invalid.", retryable: false };
    }
  }
  return { message: "This action is temporarily unavailable. No sensitive detail was exposed.", retryable: true };
}

function downloadBundle(bundle: EvidenceBundle) {
  const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `evidence-${bundle.manifest.decisionId}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function EvidenceExportPanel({
  decisionReference,
  onClose,
}: {
  decisionReference: string;
  onClose: () => void;
}) {
  const [stage, setStage] = useState<Stage>({ name: "reason-input" });
  const [reason, setReason] = useState("");

  async function submitExport() {
    setStage({ name: "exporting" });
    try {
      const bundle = await exportEvidenceThroughBff(decisionReference, reason);
      setStage({ name: "exported", bundle });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  async function submitVerify(bundle: EvidenceBundle) {
    setStage({ name: "verifying", bundle });
    try {
      const result = await verifyEvidenceBundleThroughBff(bundle);
      setStage({ name: "verified", bundle, result });
    } catch (error) {
      const { message, retryable } = errorMessage(error);
      setStage({ name: "error", message, retryable });
    }
  }

  const bundleInScope =
    stage.name === "exported" || stage.name === "verifying" || stage.name === "verified"
      ? stage.bundle
      : undefined;

  return (
    <Panel className="investigationDetailPanel">
      <SectionHeader
        eyebrow="Signed, redacted, independently verifiable"
        title="Export evidence"
        description="Exports the decision's full evidentiary context -- metadata, normalized input, reasons, replay outcome, and chain proof -- as one canonical, hashed, and signed bundle (ADR 0028). This records one append-only row in the evidence export log; no other business state is touched."
        trailing={
          <button className="button button--secondary" onClick={onClose} type="button">
            Close
          </button>
        }
      />

      {stage.name === "reason-input" ? (
        <div className="investigationRecord">
          <label htmlFor={`evidence-export-reason-${decisionReference}`}>Reason for export</label>
          <input
            id={`evidence-export-reason-${decisionReference}`}
            type="text"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={500}
          />
          <button
            className="button button--primary"
            disabled={reason.trim().length === 0}
            onClick={() => void submitExport()}
            type="button"
          >
            Export bundle
          </button>
        </div>
      ) : null}

      {stage.name === "exporting" ? <p className="muted">Exporting evidence bundle…</p> : null}

      {bundleInScope ? (
        <div className="investigationDetailContent">
          <dl className="investigationEvidenceGrid" aria-label="Evidence manifest">
            <div className="investigationEvidenceValue">
              <dt>Decision</dt>
              <dd>{bundleInScope.manifest.decisionId}</dd>
            </div>
            <div className="investigationEvidenceValue">
              <dt>Exported by</dt>
              <dd>{bundleInScope.manifest.exportedBy}</dd>
            </div>
            <div className="investigationEvidenceValue">
              <dt>Reason</dt>
              <dd>{bundleInScope.manifest.exportReason}</dd>
            </div>
            <div className="investigationEvidenceValue">
              <dt>Generated at</dt>
              <dd>{bundleInScope.manifest.generatedAt}</dd>
            </div>
            <div className="investigationEvidenceValue">
              <dt>Content hash</dt>
              <dd>
                {bundleInScope.manifest.contentHashAlgorithm} · {bundleInScope.manifest.contentHash}
              </dd>
            </div>
            <div className="investigationEvidenceValue">
              <dt>Signature algorithm</dt>
              <dd>{bundleInScope.manifest.signatureAlgorithm}</dd>
            </div>
          </dl>

          <div className="investigationRecord">
            <button
              className="button button--secondary"
              onClick={() => downloadBundle(bundleInScope)}
              type="button"
            >
              Download bundle (JSON)
            </button>
            {stage.name !== "verifying" ? (
              <button
                className="button button--secondary"
                onClick={() => void submitVerify(bundleInScope)}
                type="button"
              >
                Verify bundle
              </button>
            ) : null}
          </div>
        </div>
      ) : null}

      {stage.name === "verifying" ? <p className="muted">Verifying bundle…</p> : null}

      {stage.name === "verified" ? (
        stage.result.valid ? (
          <SafeAlert title="Bundle verified" tone="positive">
            The content hash and signature both check out against the bundle&apos;s own embedded
            public key.
          </SafeAlert>
        ) : (
          <SafeAlert title="Bundle verification failed" tone="critical">
            <ul>
              {stage.result.problems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </SafeAlert>
        )
      ) : null}

      {stage.name === "error" ? (
        <SafeAlert title="Evidence export failed" tone="critical">
          {stage.message}
          {stage.retryable ? (
            <>
              {" "}
              <button
                className="actionLink"
                onClick={() => setStage({ name: "reason-input" })}
                type="button"
              >
                Try again
              </button>
            </>
          ) : null}
        </SafeAlert>
      ) : null}
    </Panel>
  );
}
