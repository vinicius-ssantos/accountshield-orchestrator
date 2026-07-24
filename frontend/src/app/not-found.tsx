import Link from "next/link";

export default function NotFound() {
  return (
    <main className="statePage">
      <section className="stateCard" aria-labelledby="not-found-title">
        <p className="eyebrow">Not found</p>
        <h1 id="not-found-title">This console route is not available</h1>
        <p className="muted">The requested surface is not part of the current read-only foundation.</p>
        <Link className="actionLink" href="/">
          Return to overview
        </Link>
      </section>
    </main>
  );
}
