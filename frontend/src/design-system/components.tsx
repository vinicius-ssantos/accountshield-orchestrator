import Link from "next/link";
import type { ReactNode } from "react";

export type StatusTone =
  | "neutral"
  | "info"
  | "positive"
  | "attention"
  | "critical"
  | "muted";

export interface NavigationItem {
  label: string;
  href: string;
}

const DEFAULT_NAVIGATION_ITEMS: readonly NavigationItem[] = [
  { label: "Overview", href: "/" },
  { label: "Decisions", href: "/decisions" },
  { label: "Recoveries", href: "/recoveries" },
  { label: "Policies", href: "/policies" },
  { label: "Replay", href: "/replay" },
  { label: "Operations", href: "/operations" },
];

function classNames(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(" ");
}

export function AppShell({
  activeHref,
  children,
  navigationItems = DEFAULT_NAVIGATION_ITEMS,
  environmentLabel = "Fixture mode",
  environmentDetail = "no administrative mutations",
}: {
  activeHref: string;
  children: ReactNode;
  navigationItems?: readonly NavigationItem[];
  environmentLabel?: string;
  environmentDetail?: string;
}) {
  return (
    <div className="appShell">
      <a className="skipLink" href="#main-content">
        Skip to main content
      </a>

      <aside className="appSidebar" aria-label="Application sidebar">
        <div>
          <div className="appBrand">AccountShield</div>
          <p className="eyebrow">Security Operations</p>
        </div>

        <nav className="appNavigation" aria-label="Primary navigation">
          {navigationItems.map((item) => {
            const active = item.href === activeHref;
            return (
              <Link
                aria-current={active ? "page" : undefined}
                className={classNames("appNavigationLink", active && "isActive")}
                href={item.href}
                key={item.href}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="environmentNotice">
          <StatusBadge label={environmentLabel} tone="attention" />
          <span>{environmentLabel} · {environmentDetail}</span>
        </div>
      </aside>

      <main className="appContent" id="main-content" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}

export function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="pageHeader">
      <div className="pageHeaderCopy">
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        {description ? <p className="muted pageDescription">{description}</p> : null}
      </div>
      {action ? <div className="pageHeaderAction">{action}</div> : null}
    </header>
  );
}

export function Panel({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return <section className={classNames("panel", className)}>{children}</section>;
}

export function SectionHeader({
  eyebrow,
  title,
  description,
  trailing,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  trailing?: ReactNode;
}) {
  return (
    <div className="sectionHeader">
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h2>{title}</h2>
        {description ? <p className="muted sectionDescription">{description}</p> : null}
      </div>
      {trailing ? <div className="sectionHeaderTrailing">{trailing}</div> : null}
    </div>
  );
}

export function MetricCard({
  label,
  value,
  detail,
  status,
}: {
  label: string;
  value: string;
  detail: string;
  status?: ReactNode;
}) {
  return (
    <article className="metricCard">
      <div className="metricCardHeader">
        <span>{label}</span>
        {status}
      </div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </article>
  );
}

const STATUS_SYMBOLS: Record<StatusTone, string> = {
  neutral: "●",
  info: "i",
  positive: "✓",
  attention: "!",
  critical: "×",
  muted: "–",
};

export function StatusBadge({
  label,
  tone = "neutral",
}: {
  label: string;
  tone?: StatusTone;
}) {
  return (
    <span className={`statusBadge statusBadge--${tone}`} data-tone={tone}>
      <span aria-hidden="true" className="statusBadgeSymbol">
        {STATUS_SYMBOLS[tone]}
      </span>
      <span>{label}</span>
    </span>
  );
}

export function maskIdentifier(
  value: string,
  visiblePrefix = 4,
  visibleSuffix = 4,
): string {
  const normalized = value.trim();
  if (!normalized) {
    return "••••";
  }

  if (normalized.length <= visiblePrefix + visibleSuffix) {
    return `${normalized.slice(0, Math.min(2, normalized.length))}••••`;
  }

  return `${normalized.slice(0, visiblePrefix)}••••${normalized.slice(-visibleSuffix)}`;
}

export function MaskedIdentifier({
  maskedValue,
  label = "Masked identifier",
}: {
  maskedValue: string;
  label?: string;
}) {
  return (
    <code
      aria-label={`${label}: ${maskedValue}`}
      className="maskedIdentifier"
      data-masked="true"
    >
      {maskedValue}
    </code>
  );
}

export function Timestamp({
  value,
  timeZone = "UTC",
  label = "Timestamp",
}: {
  value: string | Date;
  timeZone?: string;
  label?: string;
}) {
  const date = value instanceof Date ? value : new Date(value);
  const formatted = new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone,
    timeZoneName: "short",
  }).format(date);

  return (
    <time className="timestamp" dateTime={date.toISOString()}>
      <span className="srOnly">{label}: </span>
      {formatted}
    </time>
  );
}

export interface DataTableColumn {
  key: string;
  label: string;
  align?: "start" | "end";
}

export interface DataTableRow {
  id: string;
  cells: Readonly<Record<string, ReactNode>>;
}

export function DataTable({
  caption,
  columns,
  rows,
  emptyMessage = "No records match the current view.",
}: {
  caption: string;
  columns: readonly DataTableColumn[];
  rows: readonly DataTableRow[];
  emptyMessage?: string;
}) {
  return (
    <div className="tableWrapper">
      <table className="dataTable">
        <caption className="srOnly">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th
                className={column.align === "end" ? "alignEnd" : undefined}
                key={column.key}
                scope="col"
              >
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length > 0 ? (
            rows.map((row) => (
              <tr key={row.id}>
                {columns.map((column) => (
                  <td
                    className={column.align === "end" ? "alignEnd" : undefined}
                    key={column.key}
                  >
                    {row.cells[column.key] ?? null}
                  </td>
                ))}
              </tr>
            ))
          ) : (
            <tr>
              <td className="tableEmptyCell" colSpan={columns.length}>
                {emptyMessage}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export function FilterBar({
  children,
  action,
  method = "get",
  ariaLabel = "Filter results",
}: {
  children: ReactNode;
  action?: string;
  method?: "get" | "post";
  ariaLabel?: string;
}) {
  return (
    <form action={action} aria-label={ariaLabel} className="filterBar" method={method}>
      <div className="filterFields">{children}</div>
      <button className="button button--secondary" type="submit">
        Apply filters
      </button>
    </form>
  );
}

export function FilterField({
  label,
  name,
  children,
}: {
  label: string;
  name: string;
  children: ReactNode;
}) {
  return (
    <label className="filterField">
      <span>{label}</span>
      <span className="filterControl" data-field={name}>
        {children}
      </span>
    </label>
  );
}

function pageHref(basePath: string, page: number): string {
  return `${basePath}${basePath.includes("?") ? "&" : "?"}page=${page}`;
}

export function Pagination({
  currentPage,
  pageCount,
  basePath,
}: {
  currentPage: number;
  pageCount: number;
  basePath: string;
}) {
  const pages = Array.from({ length: pageCount }, (_, index) => index + 1);

  return (
    <nav aria-label="Pagination" className="pagination">
      {currentPage > 1 ? (
        <Link className="paginationLink" href={pageHref(basePath, currentPage - 1)}>
          Previous
        </Link>
      ) : (
        <span aria-disabled="true" className="paginationLink isDisabled">
          Previous
        </span>
      )}

      <div className="paginationPages">
        {pages.map((page) => (
          <Link
            aria-current={page === currentPage ? "page" : undefined}
            className={classNames(
              "paginationLink",
              page === currentPage && "isCurrent",
            )}
            href={pageHref(basePath, page)}
            key={page}
          >
            <span className="srOnly">Page </span>
            {page}
          </Link>
        ))}
      </div>

      {currentPage < pageCount ? (
        <Link className="paginationLink" href={pageHref(basePath, currentPage + 1)}>
          Next
        </Link>
      ) : (
        <span aria-disabled="true" className="paginationLink isDisabled">
          Next
        </span>
      )}
    </nav>
  );
}

export interface TimelineItem {
  id: string;
  title: string;
  description: string;
  timestamp: string;
  status: string;
  tone?: StatusTone;
}

export function Timeline({ items }: { items: readonly TimelineItem[] }) {
  return (
    <ol className="timeline">
      {items.map((item) => (
        <li className="timelineItem" key={item.id}>
          <div className="timelineMarker" aria-hidden="true" />
          <div className="timelineContent">
            <div className="timelineHeader">
              <strong>{item.title}</strong>
              <StatusBadge label={item.status} tone={item.tone} />
            </div>
            <p>{item.description}</p>
            <Timestamp label={`${item.title} time`} value={item.timestamp} />
          </div>
        </li>
      ))}
    </ol>
  );
}

export type ApplicationStateKind =
  | "loading"
  | "empty"
  | "degraded"
  | "unavailable"
  | "unauthorized"
  | "forbidden";

const STATE_TONES: Record<ApplicationStateKind, StatusTone> = {
  loading: "info",
  empty: "muted",
  degraded: "attention",
  unavailable: "critical",
  unauthorized: "attention",
  forbidden: "critical",
};

export function ApplicationState({
  kind,
  title,
  description,
  action,
}: {
  kind: ApplicationStateKind;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <section
      aria-live={kind === "loading" ? "polite" : undefined}
      className="applicationState"
      data-state={kind}
    >
      <StatusBadge label={kind} tone={STATE_TONES[kind]} />
      <h3>{title}</h3>
      <p>{description}</p>
      {action ? <div className="applicationStateAction">{action}</div> : null}
    </section>
  );
}

export function SafeAlert({
  title,
  children,
  tone = "info",
}: {
  title: string;
  children: ReactNode;
  tone?: Exclude<StatusTone, "neutral" | "muted">;
}) {
  return (
    <div
      className={`safeAlert safeAlert--${tone}`}
      role={tone === "critical" ? "alert" : "status"}
    >
      <StatusBadge label={title} tone={tone} />
      <div>{children}</div>
    </div>
  );
}
