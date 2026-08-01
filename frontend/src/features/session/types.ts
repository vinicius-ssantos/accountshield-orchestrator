export type SessionStatus =
  | { authenticated: false; state: "expired" | "absent" }
  | { authenticated: true; state: "valid"; subject: string; roles: readonly string[]; expiresAt: string };

export interface LoginCredentials {
  username: string;
  password: string;
}
