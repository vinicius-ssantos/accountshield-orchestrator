import { BffError } from "../foundation";
import type { BackendTokenResponse, SessionTokenService } from "./session";

// Mirrors the published demo personas documented in backend ADR 0046 and on the login page --
// fixture mode never talks to a real backend, so this simulates the same credential check and
// issues a locally-shaped (never cryptographically verified) fixture token, the same trust model
// every other fixture data source in this codebase already uses.
const FIXTURE_PERSONAS: Readonly<Record<string, { password: string; roles: readonly string[] }>> = {
  "operator-1": { password: "accountshield-demo-operator", roles: ["SECURITY_OPERATOR"] },
  "analyst-1": { password: "accountshield-demo-analyst", roles: ["SIMULATION_ANALYST"] },
  "admin-1": { password: "accountshield-demo-admin", roles: ["POLICY_ADMIN"] },
  "reader-1": { password: "accountshield-demo-reader", roles: ["OBSERVABILITY_READER"] },
};

const FIXTURE_TOKEN_TTL_MS = 15 * 60 * 1000;

function encodeSegment(value: unknown): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function buildFixtureToken(subject: string, roles: readonly string[]): BackendTokenResponse {
  const header = encodeSegment({ alg: "none", typ: "fixture" });
  const payload = encodeSegment({ sub: subject, roles });
  return {
    token: `${header}.${payload}.fixture`,
    expiresAt: new Date(Date.now() + FIXTURE_TOKEN_TTL_MS).toISOString(),
  };
}

function decodeFixtureToken(token: string): { subject: string; roles: readonly string[] } {
  const segments = token.split(".");
  if (segments.length !== 3) {
    throw new BffError("UNAUTHORIZED", 401, "The session could not be refreshed.");
  }
  try {
    const payload = JSON.parse(Buffer.from(segments[1], "base64url").toString("utf8")) as {
      sub: string;
      roles: string[];
    };
    return { subject: payload.sub, roles: payload.roles };
  } catch {
    throw new BffError("UNAUTHORIZED", 401, "The session could not be refreshed.");
  }
}

export function fixtureSessionTokenService(): SessionTokenService {
  return {
    async issueToken(credentials) {
      const persona = FIXTURE_PERSONAS[credentials.username];
      if (!persona || persona.password !== credentials.password) {
        throw new BffError("UNAUTHORIZED", 401, "Invalid credentials.");
      }
      return buildFixtureToken(credentials.username, persona.roles);
    },
    async refreshToken(currentToken) {
      const { subject, roles } = decodeFixtureToken(currentToken);
      return buildFixtureToken(subject, roles);
    },
  };
}
