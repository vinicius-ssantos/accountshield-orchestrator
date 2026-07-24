export default function Loading() {
  return (
    <main className="statePage" aria-live="polite" aria-busy="true">
      <section className="stateCard" aria-labelledby="loading-title">
        <p className="eyebrow">Loading</p>
        <h1 id="loading-title">Preparing the operations console</h1>
        <p className="muted">Loading fixture-backed security operations data.</p>
      </section>
    </main>
  );
}
