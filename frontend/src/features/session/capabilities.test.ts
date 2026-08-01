import { describe, expect, it } from "vitest";

import { capabilitiesForRoles, primaryRoleLabel } from "./capabilities";

describe("capabilitiesForRoles", () => {
  it("grants operator console capabilities for SECURITY_OPERATOR", () => {
    expect(capabilitiesForRoles(["SECURITY_OPERATOR"])).toEqual({
      canViewDecisions: true,
      canViewRecoveries: true,
      canViewPolicies: true,
      canViewOutbox: true,
    });
  });

  it("degrades safely for a role with no defined capabilities, never crashing", () => {
    expect(capabilitiesForRoles(["POLICY_ADMIN"])).toEqual({
      canViewDecisions: false,
      canViewRecoveries: false,
      canViewPolicies: false,
      canViewOutbox: false,
    });
  });

  it("degrades safely for an empty or unknown role list", () => {
    expect(capabilitiesForRoles([])).toEqual({
      canViewDecisions: false,
      canViewRecoveries: false,
      canViewPolicies: false,
      canViewOutbox: false,
    });
    expect(capabilitiesForRoles(["SOMETHING_UNEXPECTED"])).toEqual({
      canViewDecisions: false,
      canViewRecoveries: false,
      canViewPolicies: false,
      canViewOutbox: false,
    });
  });
});

describe("primaryRoleLabel", () => {
  it("returns the first role", () => {
    expect(primaryRoleLabel(["SECURITY_OPERATOR", "OBSERVABILITY_READER"])).toBe("SECURITY_OPERATOR");
  });

  it("falls back to a generic label for an empty role list", () => {
    expect(primaryRoleLabel([])).toBe("OPERATOR");
  });
});
