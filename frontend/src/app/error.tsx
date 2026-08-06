"use client";

import Link from "next/link";

export default function ErrorState({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main className="statePage" role="alert">
      <section className="stateCard" aria-labelledby="error-title">
        <p className="eyebrow">Unable to load</p>
        <h1 id="error-title">The operations console could not complete this request</h1>
        <p className="muted">
          No sensitive error details are exposed in the browser. Retry the request or return to the overview.
        </p>
        <div className="stateActions">
          <button type="button" onClick={reset}>
            Retry
          </button>
          <Link className="secondaryLink" href="/">
            Return to overview
          </Link>
        </div>
      </section>
    </main>
  );
}
