import Link from "next/link";
import { notFound } from "next/navigation";

const sections = {
  decisions: {
    title: "Decisions",
    description: "Search and investigate explainable account-protection decisions by correlation ID.",
  },
  recoveries: {
    title: "Recoveries",
    description: "Review recovery workflows after backend RBAC and fresh step-up authorization are available.",
  },
  policies: {
    title: "Policies",
    description: "Compare policy versions and inspect rollout impact without enabling mutations yet.",
  },
  replay: {
    title: "Replay",
    description: "Reproduce historical decisions and compare candidate policy outcomes without side effects.",
  },
  operations: {
    title: "Operations",
    description: "Inspect outbox, delivery, and dead-letter health when operational read models are available.",
  },
} as const;

type Section = keyof typeof sections;

export default async function PlannedSectionPage({
  params,
}: {
  params: Promise<{ section: string }>;
}) {
  const { section } = await params;
  const configuration = sections[section as Section];

  if (!configuration) {
    notFound();
  }

  return (
    <main className="statePage">
      <section className="stateCard" aria-labelledby="section-title">
        <p className="eyebrow">Planned read-only surface</p>
        <h1 id="section-title">{configuration.title}</h1>
        <p className="muted">{configuration.description}</p>
        <p className="stateNotice">
          This route is intentionally available before live API integration so navigation remains stable while
          backend contracts evolve.
        </p>
        <Link className="actionLink" href="/">
          Return to overview
        </Link>
      </section>
    </main>
  );
}
