"use client";

import { useSession } from "@/features/session/session-context";
import { PolicyDirectoryConsole } from "@/features/policies/policy-directory-console";

// Lives in the app layer, not features/policies, because features must not import
// features/session directly (ARCH009 forbids cross-feature imports) -- this bridge is the one
// place allowed to depend on both, composing them for the page.
export function PolicyDirectoryConsoleWithSession() {
  const { status } = useSession();
  const currentSubject = status?.authenticated ? status.subject : undefined;
  return <PolicyDirectoryConsole currentSubject={currentSubject} />;
}
