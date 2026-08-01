/**
 * Maps an authenticated operator's roles to presentation capability flags. This is a UX
 * convenience ONLY -- hiding or showing a control here never substitutes for authorization.
 * Every protected read/write remains enforced by the backend (role checks) and the BFF session
 * guard (require-session.ts); an operator whose role is misreported here would simply see a
 * control that then fails against the real, authoritative checks, never gain access through it.
 */
export interface OperatorCapabilities {
  canViewDecisions: boolean;
  canViewRecoveries: boolean;
  canViewPolicies: boolean;
  canViewOutbox: boolean;
}

const NO_CAPABILITIES: OperatorCapabilities = {
  canViewDecisions: false,
  canViewRecoveries: false,
  canViewPolicies: false,
  canViewOutbox: false,
};

export function capabilitiesForRoles(roles: readonly string[]): OperatorCapabilities {
  const roleSet = new Set(roles);
  if (!roleSet.has("SECURITY_OPERATOR")) {
    return NO_CAPABILITIES;
  }
  return {
    canViewDecisions: true,
    canViewRecoveries: true,
    canViewPolicies: true,
    canViewOutbox: true,
  };
}

export function primaryRoleLabel(roles: readonly string[]): string {
  return roles[0] ?? "OPERATOR";
}
