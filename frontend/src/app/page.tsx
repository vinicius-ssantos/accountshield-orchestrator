const metrics = [
  { label: "Decisions today", value: "1,284", detail: "Synthetic fixture data" },
  { label: "High-risk events", value: "37", detail: "2.9% of evaluated events" },
  { label: "Manual reviews", value: "12", detail: "Read-only until RBAC is ready" },
  { label: "Replay divergences", value: "3", detail: "Candidate policy comparison" }
];

const decisions = [
  { id: "cor_8f12…", event: "LOGIN", risk: 82, outcome: "START_RECOVERY", policy: "v7" },
  { id: "cor_a921…", event: "PASSWORD_CHANGE", risk: 64, outcome: "TEMPORARILY_BLOCK", policy: "v7" },
  { id: "cor_120c…", event: "LOGIN", risk: 41, outcome: "REQUIRE_STEP_UP", policy: "v7" },
  { id: "cor_77bd…", event: "SENSITIVE_ACTION", risk: 18, outcome: "ALLOW", policy: "v7" }
];

export default function Home() {
  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand">AccountShield</div>
        <p className="eyebrow">Security Operations</p>
        <nav>
          {["Overview", "Decisions", "Recoveries", "Policies", "Replay", "Operations"].map((item, index) => (
            <a className={index === 0 ? "active" : ""} href="#" key={item}>{item}</a>
          ))}
        </nav>
        <div className="notice">Fixture mode · no administrative mutations</div>
      </aside>

      <section className="content">
        <header>
          <div>
            <p className="eyebrow">Operations overview</p>
            <h1>Account protection at a glance</h1>
            <p className="muted">Investigate decisions, explain risk, and prepare safe operator workflows.</p>
          </div>
          <button>Search correlation ID</button>
        </header>

        <section className="metrics">
          {metrics.map((metric) => (
            <article className="card" key={metric.label}>
              <span>{metric.label}</span>
              <strong>{metric.value}</strong>
              <small>{metric.detail}</small>
            </article>
          ))}
        </section>

        <section className="panel">
          <div className="panelHeader">
            <div>
              <p className="eyebrow">Recent decisions</p>
              <h2>Investigation queue</h2>
            </div>
            <span className="badge">Read only</span>
          </div>
          <div className="table">
            <div className="row heading"><span>Correlation</span><span>Event</span><span>Risk</span><span>Outcome</span><span>Policy</span></div>
            {decisions.map((decision) => (
              <div className="row" key={decision.id}>
                <code>{decision.id}</code>
                <span>{decision.event}</span>
                <span>{decision.risk}</span>
                <span className="outcome">{decision.outcome}</span>
                <span>{decision.policy}</span>
              </div>
            ))}
          </div>
        </section>
      </section>
    </main>
  );
}
