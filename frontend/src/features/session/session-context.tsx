"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

import { fetchSessionStatus, logout as logoutRequest } from "./session-browser";
import type { SessionStatus } from "./types";

const BROADCAST_CHANNEL_NAME = "accountshield-session";
const LOGIN_PATH = "/login";

interface SessionContextValue {
  status: SessionStatus | undefined;
  loading: boolean;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<SessionContextValue | undefined>(undefined);

/** Forces a full page navigation rather than a soft client-side route change, so no protected
 * component state, in-memory cache, or React tree survives a logout -- consistent with this
 * codebase's server-driven-over-client-cache style. */
function navigateToLogin(): void {
  window.location.assign(LOGIN_PATH);
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus | undefined>(undefined);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    fetchSessionStatus({ signal: controller.signal })
      .then((result) => setStatus(result))
      .catch(() => setStatus({ authenticated: false, state: "absent" }))
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (typeof BroadcastChannel === "undefined") return undefined;
    const channel = new BroadcastChannel(BROADCAST_CHANNEL_NAME);
    channel.onmessage = (event) => {
      if (event.data === "logout") {
        navigateToLogin();
      }
    };
    return () => channel.close();
  }, []);

  async function signOut(): Promise<void> {
    try {
      await logoutRequest();
    } finally {
      if (typeof BroadcastChannel !== "undefined") {
        const channel = new BroadcastChannel(BROADCAST_CHANNEL_NAME);
        channel.postMessage("logout");
        channel.close();
      }
      navigateToLogin();
    }
  }

  return <SessionContext.Provider value={{ status, loading, signOut }}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("useSession must be used within a SessionProvider.");
  }
  return context;
}
