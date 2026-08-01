import type { LoginCredentials, SessionStatus } from "./types";

const STATUS_ENDPOINT = "/api/bff/session/status";
const LOGIN_ENDPOINT = "/api/bff/session/login";
const LOGOUT_ENDPOINT = "/api/bff/session/logout";
const CSRF_COOKIE_NAME = "as_csrf";
const CSRF_HEADER_NAME = "x-as-csrf-token";

export class SessionBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super("Session request failed.");
    this.name = "SessionBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

/** Reads the non-HttpOnly double-submit CSRF cookie so it can be echoed back as a header on
 * mutating requests. This cookie is intentionally readable by client JS -- it is a CSRF token,
 * not a credential; the session cookie itself remains HttpOnly and is never read here. */
function readCsrfToken(): string | undefined {
  if (typeof document === "undefined") return undefined;
  for (const part of document.cookie.split(";")) {
    const separatorIndex = part.indexOf("=");
    if (separatorIndex <= 0) continue;
    const name = part.slice(0, separatorIndex).trim();
    if (name === CSRF_COOKIE_NAME) {
      return decodeURIComponent(part.slice(separatorIndex + 1).trim());
    }
  }
  return undefined;
}

async function safeProblem(response: Response): Promise<SessionBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value) && typeof value.code === "string") {
      return new SessionBrowserError(value.code, response.status);
    }
  } catch {
    // Deliberately discard malformed upstream/browser-facing details.
  }
  return new SessionBrowserError("REQUEST_FAILED", response.status);
}

function parseStatus(value: unknown): SessionStatus {
  if (!isRecord(value) || typeof value.authenticated !== "boolean") {
    throw new SessionBrowserError("MALFORMED_RESPONSE", 502);
  }
  if (!value.authenticated) {
    if (value.state !== "expired" && value.state !== "absent") {
      throw new SessionBrowserError("MALFORMED_RESPONSE", 502);
    }
    return { authenticated: false, state: value.state };
  }
  if (
    value.state !== "valid" ||
    typeof value.subject !== "string" ||
    !Array.isArray(value.roles) ||
    !value.roles.every((role) => typeof role === "string") ||
    typeof value.expiresAt !== "string"
  ) {
    throw new SessionBrowserError("MALFORMED_RESPONSE", 502);
  }
  return {
    authenticated: true,
    state: "valid",
    subject: value.subject,
    roles: value.roles as string[],
    expiresAt: value.expiresAt,
  };
}

export async function fetchSessionStatus(
  options: { signal?: AbortSignal; fetchImplementation?: typeof fetch } = {},
): Promise<SessionStatus> {
  const fetchImplementation = options.fetchImplementation ?? fetch;
  const response = await fetchImplementation(STATUS_ENDPOINT, {
    method: "GET",
    headers: { accept: "application/json" },
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });
  if (!response.ok) throw await safeProblem(response);
  return parseStatus((await response.json()) as unknown);
}

export async function login(
  credentials: LoginCredentials,
  options: { fetchImplementation?: typeof fetch } = {},
): Promise<void> {
  const fetchImplementation = options.fetchImplementation ?? fetch;
  const response = await fetchImplementation(LOGIN_ENDPOINT, {
    method: "POST",
    headers: { accept: "application/json", "content-type": "application/json" },
    body: JSON.stringify(credentials),
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await safeProblem(response);
}

export async function logout(options: { fetchImplementation?: typeof fetch } = {}): Promise<void> {
  const fetchImplementation = options.fetchImplementation ?? fetch;
  const csrfToken = readCsrfToken();
  const headers: Record<string, string> = { accept: "application/json" };
  if (csrfToken) headers[CSRF_HEADER_NAME] = csrfToken;

  const response = await fetchImplementation(LOGOUT_ENDPOINT, {
    method: "POST",
    headers,
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) throw await safeProblem(response);
}
