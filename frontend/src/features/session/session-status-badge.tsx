"use client";

import Link from "next/link";

import { StatusBadge } from "@/design-system/components";

import { primaryRoleLabel } from "./capabilities";
import { useSession } from "./session-context";

export function SessionStatusBadge() {
  const { status, loading, signOut } = useSession();

  if (loading || !status) {
    return <StatusBadge label="Checking session…" tone="muted" />;
  }

  if (!status.authenticated) {
    return (
      <div className="sessionStatus">
        <StatusBadge label="Not signed in" tone="muted" />
        <Link className="sessionStatusLink" href="/login">
          Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="sessionStatus">
      <StatusBadge label={`Signed in as ${status.subject}`} tone="positive" />
      <span className="muted">{primaryRoleLabel(status.roles)}</span>
      <button className="sessionStatusLink" onClick={() => void signOut()} type="button">
        Sign out
      </button>
    </div>
  );
}
