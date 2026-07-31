import { describe, expect, it, vi } from "vitest";

import {
  PolicyInvestigationBrowserError,
  investigatePolicyThroughBff,
} from "./policy-investigation-browser";

const DETAIL = {
  policyKey: "account-protection-default",
  versions: [
    {
      id: "00000000-0000-4000-a000-000000000002",
      policyKey: "account-protection-default",
      version: "1.0.0",
      status: "ACTIVE",
      allowMaxScore: 25,
      stepUpMaxScore: 65,
      recoveryMaxScore: 89,
      createdAt: "2026-06-01T09:00:00.000Z",
      activatedAt: "2026-06-02T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-06-01T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-06-02T08:00:00.000Z",
        approvalReason: "Initial default policy.",
      },
    },
  ],
  routingScope: [{ clientId: "default-client", eventType: "LOGIN_ATTEMPT" }],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
  source: "fixtures",
};

describe("browser policy-investigation transport", () => {
  it("posts the policy key in a same-origin body instead of the URL", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(DETAIL), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await investigatePolicyThroughBff("account-protection-default", {
      fetchImplementation,
    });

    expect(result.policyKey).toBe("account-protection-default");
    expect(result.versions).toHaveLength(1);
    expect(result.versions[0]?.governance?.approvedBy).toBe("policy-approver");
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/policy-investigation");
    expect(url).not.toContain("account-protection-default");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ policyKey: "account-protection-default" });
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
  });

  it("keeps arbitrary problem details out of browser errors", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({ code: "NOT_FOUND", detail: "raw backend persistence detail", retryable: false }),
        { status: 404, headers: { "content-type": "application/problem+json" } },
      ),
    );

    const promise = investigatePolicyThroughBff("unknown-policy", { fetchImplementation });

    await expect(promise).rejects.toBeInstanceOf(PolicyInvestigationBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      retryable: false,
      message: "Policy investigation failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ versions: null }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(
      investigatePolicyThroughBff("account-protection-default", { fetchImplementation }),
    ).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
