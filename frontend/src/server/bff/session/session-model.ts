export interface SessionRecord {
  sessionId: string;
  subject: string;
  roles: readonly string[];
  backendToken: string;
  backendTokenExpiresAt: number;
  csrfSecret: string;
  createdAt: number;
  lastSeenAt: number;
  absoluteExpiresAt: number;
}

export type SessionLookupState = "valid" | "expired" | "absent";

export type SessionLookupResult =
  | { state: "valid"; record: SessionRecord }
  | { state: "expired" | "absent"; record: undefined };
