import { fixtureDecisionsDataSource } from "@/features/decisions/fixtures";

const navigationItems = ["Overview", "Decisions", "Recoveries", "Policies", "Replay", "Operations"] as const;

export default async function Home() {
  const [metrics, decisions] = await Promise.all([
    fixtureDecisionsDataSource.listOverviewMetrics(),
    fixtureDecisionsDataSource.listRecent(),
  ]);

  return (
    <main className="shell">
      <aside className="sidebar" aria-label="Primary navigation">
        <div className="brand">AccountShield</div>
        <p className="eyebrow">Security Operations</p>
        <nav>
          {navigationItems.map((item, index) => (
            <a
              aria-current={index === 0 ? "page" : undefined}
              className={index === 0 ? "active" : ""}
              href={index === 0 ? "/" : `/${item.toLowerCase()}`}
              key={item}
            >
              {item}
            </a>
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
          <a className="actionLink" href="/decisions">
            Search correlation ID
          </a>
        </header>

        <section aria-label="Operations metrics" className="metrics">
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

          <div className="tableWrapper">
            <table>
              <caption className="srOnly">Recent account-protection decisions</caption>
              <thead>
                <tr>
                  <th scope="col">Correlation</th>
                  <th scope="col">Event</th>
                  <th scope="col">Risk</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Policy</th>
                </tr>
              </thead>
              <tbody>
                {decisions.map((decision) => (
                  <tr key={decision.correlationId}>
                    <td><code>{decision.correlationId}</code></td>
                    <td>{decision.eventType}</td>
                    <td>{decision.riskScore}</td>
                    <td><span className="outcome">{decision.outcome}</span></td>
                    <td>{decision.policyVersion}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </section>
    </main>
  );
}
