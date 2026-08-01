"use client";

import { useState, type FormEvent } from "react";

import { SafeAlert } from "@/design-system/components";

import { login } from "./session-browser";

export function LoginForm() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    try {
      await login({ username, password });
      window.location.assign("/");
    } catch {
      // Deliberately generic: never confirm or deny whether a username exists.
      setError("Invalid username or password.");
      setSubmitting(false);
    }
  }

  return (
    <form aria-label="Operator sign in" className="loginForm panel" onSubmit={(event) => void handleSubmit(event)}>
      {error ? (
        <SafeAlert title="Sign in failed" tone="critical">
          {error}
        </SafeAlert>
      ) : null}

      <label className="filterField">
        <span>Username</span>
        <input
          autoComplete="username"
          name="username"
          onChange={(event) => setUsername(event.target.value)}
          required
          type="text"
          value={username}
        />
      </label>

      <label className="filterField">
        <span>Password</span>
        <input
          autoComplete="current-password"
          name="password"
          onChange={(event) => setPassword(event.target.value)}
          required
          type="password"
          value={password}
        />
      </label>

      <button className="button" disabled={submitting} type="submit">
        {submitting ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}
