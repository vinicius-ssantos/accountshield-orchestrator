# AccountShield console design system

The AccountShield design system is a deliberately small set of tokens and React patterns for the internal Security Operations Console. It is not a public component package and it must optimize for operational clarity, accessibility, deterministic tests, and safe handling of sensitive identifiers.

## Source of truth

- tokens and global component styles: `src/app/globals.css`;
- reusable React patterns: `src/design-system/components.tsx`;
- component tests: `src/design-system/components.test.tsx`;
- browser and accessibility examples: `src/app/design-system/page.tsx` and `e2e/design-system.spec.ts`.

Do not create page-local copies of status badges, tables, application states, masking, pagination, timeline, filter, focus, or alert behavior. Extend the shared pattern or document why the use case is materially different.

## Tokens

Tokens are grouped by intent rather than by page:

- typography: font families, sizes, line heights;
- spacing and density: spacing scale, control height, table row height;
- shape and elevation: radii, border width, surface shadow;
- motion: fast and normal durations plus the shared easing curve;
- neutral palette: canvas, surfaces, borders, text, focus, links;
- semantic operational palette: neutral, information, positive, attention, critical, and muted states.

Semantic tokens must be selected by meaning. Do not choose a critical color merely because it looks visually prominent.

## Semantic status rules

`StatusBadge` always renders a stable text label and a non-color symbol. The visible label is the source of truth for screenshots, tests, screen readers, and incident communication.

Recommended mappings:

| Domain | Examples |
| --- | --- |
| Risk | `Low risk`, `Medium risk`, `High risk` |
| Decision | `Allowed`, `Step up required`, `Denied` |
| Lifecycle | `Pending`, `Active`, `Expired`, `Cancelled` |
| Operations | `Healthy`, `Degraded`, `Unavailable` |
| Data provenance | `Fixture data`, `Simulated`, `Live`, `Stale` |

Never render an unlabeled colored dot as the only status signal.

## Masked identifiers

Raw sensitive values must be reduced on the server with `maskIdentifier` before they are passed to `MaskedIdentifier`. Only `maskedValue` is rendered.

The component does not add a title, hidden raw value, tooltip, data attribute, or alternate accessible label containing the original identifier. Copying the visible text therefore copies only the masked representation.

Do not:

- pass a raw identifier to a Client Component for masking;
- place raw values in `data-*` attributes;
- expose raw values through analytics labels, accessible descriptions, tooltips, debug output, or hydration payloads;
- add reveal-on-hover behavior.

A future reveal action requires an explicit authorization and audit design outside this component.

## Server and Client Component ownership

Prefer Server Components for:

- `AppShell`, `PageHeader`, panels, metric cards, status badges;
- tables and timelines backed by server query results;
- timestamp formatting with an explicit timezone;
- masked values after server-side reduction;
- URL-driven filters and pagination;
- empty, degraded, unavailable, unauthorized, and forbidden states.

Use a Client Component only when the interaction requires local state, focus coordination, optimistic feedback, or browser-only APIs. Keep the Client boundary around the smallest interactive control and pass only the minimum already-redacted data.

## Forms, filters, and pagination

Start with native form controls and URL query parameters. Every control needs a visible label. Filter changes must not imply that a mutation occurred.

Pagination uses real links so navigation remains bookmarkable, keyboard-operable, and progressively enhanced. Disabled controls are rendered as non-interactive elements with `aria-disabled="true"`.

## Application states

Use `ApplicationState` for loading, empty, degraded, unavailable, unauthorized, and forbidden outcomes. Each state must include:

1. a stable text label;
2. a specific title;
3. operator guidance that does not overstate certainty;
4. an action only when it is safe and meaningful.

Loading must not look like an empty result. Degraded cached data must not look live. Unauthorized and forbidden states must not disclose whether a hidden resource exists.

## Safe alerts and future dangerous actions

`SafeAlert` communicates informational, positive, attention, or critical context. Critical alerts use an assertive live region; other alerts use a polite status region.

A future dangerous action must include explicit consequence text, affected scope, reversibility, confirmation behavior, authorization requirements, audit evidence, and failure semantics. An icon, color, or generic `Are you sure?` prompt is insufficient.

## Accessibility baseline

- keyboard focus uses one visible focus token;
- a skip link targets the main content region;
- native controls are preferred;
- semantic meaning never depends on color alone;
- reduced-motion preferences reduce transitions and animations to effectively zero;
- tables include captions and column scopes;
- timestamps include machine-readable `dateTime` values and explicit display timezones;
- the internal showcase and overview must have no critical or serious axe violations.

## Contribution review

A design-system change must answer:

- Which existing shared pattern is insufficient?
- Does the change alter security, masking, accessibility, or semantic status behavior?
- Can #69–#74 consume it without page-specific branching?
- Is a Client Component truly required?
- Are stable unit and browser assertions included?
- Does it introduce a dependency? If so, where are bundle, accessibility, maintenance, and supply-chain reviews recorded?

Third-party component libraries are not allowed by default. Adoption requires a dedicated decision record and review of bundle cost, accessibility behavior, maintenance health, transitive dependencies, release cadence, and compromise response.

## Non-goals

- public branding exploration;
- decorative motion or visual effects that reduce scan speed;
- a generic enterprise UI framework;
- a standalone npm package;
- client-side storage of sensitive operator or account data;
- mutation workflows before their authorization, consequence, and audit contracts exist.
