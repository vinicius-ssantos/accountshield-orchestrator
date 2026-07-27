import { existsSync } from "node:fs";
import { readFile, readdir } from "node:fs/promises";
import { dirname, extname, posix, relative, resolve, sep } from "node:path";
import { applyExceptions, validateExceptions, violation } from "./architecture-rules.mjs";

const EXTENSIONS = new Set([".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"]);
const IGNORED = new Set(["node_modules", ".next", "coverage", "test-results", "playwright-report", "dist", "build"]);
const PRESENTATION = new Set(["app", "features", "design-system"]);
const MUTATION = /\bmethod\s*:\s*["'](?:POST|PUT|PATCH|DELETE)["']/i;
const norm = (value) => value.split(sep).join("/");
const lineAt = (text, index) => text.slice(0, index).split("\n").length;
const isTest = (path) => /(?:^|\/)(?:__tests__\/|[^/]+\.(?:test|spec)\.[^.]+$)/.test(path);
const layer = (path) => path.match(/^src\/([^/]+)/)?.[1] ?? "other";
const feature = (path) => path.match(/^src\/features\/([^/]+)\//)?.[1] ?? null;
const fixture = (path) => /(?:^|\/)fixtures(?:\.[^.]+|\/)/.test(path);
const useClient = (text) => /^\s*["']use client["'];/.test(text.slice(0, 1200).replace(/^\s*\/\*[\s\S]*?\*\//, ""));

function importsOf(text) {
  const found = [];
  for (const pattern of [
    /\b(?:import|export)\s+(?:type\s+)?(?:[\w*$\s{},]+\s+from\s+)?["']([^"']+)["']/g,
    /\bimport\s*\(\s*["']([^"']+)["']\s*\)/g,
  ]) for (const match of text.matchAll(pattern)) found.push({ specifier: match[1], index: match.index ?? 0 });
  const seen = new Set();
  return found.filter((item) => { const key = `${item.specifier}:${item.index}`; if (seen.has(key)) return false; seen.add(key); return true; });
}

async function walk(directory, root, files) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && IGNORED.has(entry.name)) continue;
    const absolute = resolve(directory, entry.name);
    if (entry.isDirectory()) await walk(absolute, root, files);
    else if (EXTENSIONS.has(extname(entry.name))) {
      const path = norm(relative(root, absolute));
      files.set(path, { path, content: await readFile(absolute, "utf8") });
    }
  }
}

function resolveImport(importer, specifier, paths) {
  let base;
  if (specifier.startsWith("@/")) base = `src/${specifier.slice(2)}`;
  else if (specifier.startsWith(".")) {
    base = posix.normalize(posix.join(posix.dirname(importer), specifier));
    if (base === ".." || base.startsWith("../")) return null;
  } else return null;
  const candidates = [base];
  if (!EXTENSIONS.has(extname(base))) {
    for (const extension of EXTENSIONS) candidates.push(`${base}${extension}`);
    for (const extension of EXTENSIONS) candidates.push(`${base}/index${extension}`);
  }
  return candidates.find((candidate) => paths.has(candidate)) ?? null;
}

function cycles(graph) {
  const result = [], state = new Map(), stack = [], seen = new Set();
  function visit(node) {
    state.set(node, 1); stack.push(node);
    for (const next of graph.get(node) ?? []) {
      if (!state.get(next)) visit(next);
      else if (state.get(next) === 1) {
        const cycle = [...stack.slice(stack.indexOf(next)), next];
        const key = cycle.slice(0, -1).sort().join("|");
        if (!seen.has(key)) { seen.add(key); result.push(cycle); }
      }
    }
    stack.pop(); state.set(node, 2);
  }
  for (const node of graph.keys()) if (!state.get(node)) visit(node);
  return result;
}

function readOnlyClosure(graph, scopes) {
  const queue = [...graph.keys()].filter((path) => !isTest(path) && scopes.some((scope) => path.startsWith(scope)));
  const result = new Set(queue);
  while (queue.length) for (const target of graph.get(queue.shift()) ?? []) if (!result.has(target)) { result.add(target); queue.push(target); }
  return result;
}

export async function analyzeProject({ projectRoot, config, today = new Date().toISOString().slice(0, 10) }) {
  const files = new Map();
  await walk(resolve(projectRoot, "src"), projectRoot, files);
  const paths = new Set(files.keys());
  const graph = new Map([...paths].map((path) => [path, new Set()]));
  const fileImports = new Map();
  for (const file of files.values()) {
    const imports = importsOf(file.content).map((item) => ({ ...item, target: resolveImport(file.path, item.specifier, paths), line: lineAt(file.content, item.index) }));
    fileImports.set(file.path, imports);
    for (const item of imports) if (item.target) graph.get(file.path).add(item.target);
  }

  const serverOnly = new Set();
  for (const file of files.values()) if (
    file.path.startsWith("src/server/") || /(?:^|\n)\s*import\s+["']server-only[#']/.test(file.content) ||
    /process\.env\.(?!NEXT_PUBLIC_)[A-Z0-9_]+/.test(file.content)) serverOnly.add(file.path);
  for (let changed = true; changed;) {
    changed = false;
    for (const [file, targets] of graph) if (!serverOnly.has(file) && [...targets].some((target) => serverOnly.has(target))) { serverOnly.add(file); changed = true; }
  }

  const violations = [];
  const publicEnv = new Set(config.publicEnvAllowlist ?? []);
  for (const file of files.values()) {
    const fromLayer = layer(file.path), client = useClient(file.content), test = isTest(file.path);
    for (const item of fileImports.get(file.path) ?? []) {
      if (client && (item.specifier === "server-only" || item.target && serverOnly.has(item.target)))
        violations.push(violation("ARCH001", file.path, item.line, `client component imports server-only module ${item.specifier}`, item.target ?? item.specifier));
      if (item.target?.startsWith("src/generated/") && fromLayer !== "generated" &&
        !(config.generatedImportAllowedPrefixes ?? []).some((prefix) => file.path.startsWith(prefix)))
        violations.push(violation("ARCH002", file.path, item.line, `direct generated import ${item.specifier}`, item.target));
      if (item.target && fixture(item.target) && PRESENTATION.has(fromLayer)) {
        const allowed = feature(file.path) && feature(file.path) === feature(item.target) && /\/get-data-source\.[^.]+$/.test(file.path);
        if (!allowed && !test) violations.push(violation("ARCH003", file.path, item.line, `fixture import bypasses data source: ${item.specifier}`, item.target));
      }
      if (item.target) {
        const toLayer = layer(item.target);
        const forbidden = fromLayer === "design-system" ? ["app", "features", "server", "generated"].includes(toLayer)
          : fromLayer === "features" ? ["app", "server", "generated"].includes(toLayer)
          : fromLayer === "server" ? ["app", "features", "design-system"].includes(toLayer)
          : fromLayer === "generated" ? toLayer !== "generated"
          : fromLayer === "config" ? ["app", "features", "server", "generated", "design-system"].includes(toLayer) : false;
        if (forbidden) violations.push(violation("ARCH008", file.path, item.line, `${fromLayer} must not import ${toLayer}: ${item.specifier}`, item.target));
        const fromFeature = feature(file.path), toFeature = feature(item.target);
        if (fromFeature && toFeature && fromFeature !== toFeature)
          violations.push(violation("ARCH009", file.path, item.line, `feature ${fromFeature} imports feature ${toFeature}`, item.target));
      }
    }

    if (!test) for (const pattern of [/process\.env\.(NEXT_PUBLIC_[A-Z0-9_]+)/g, /process\.env\[["'](NEXT_PUBLIC_[A-Z0-9_]+)["']\]/g])
      for (const match of file.content.matchAll(pattern)) if (!publicEnv.has(match[1]))
        violations.push(violation("ARCH004", file.path, lineAt(file.content, match.index ?? 0), `${match[1]} is not allowlisted`));
    if (client && /\bACCOUNTSHIELD_API_URL\b|process\.env\.(?!NEXT_PUBLIC_)[A-Z0-9_]+/.test(file.content))
      violations.push(violation("ARCH001", file.path, 1, "client component references server environment or backend origin"));
    if (PRESENTATION.has(fromLayer) && !test) for (const pattern of [
      /\bfetch\s*\(\s*["']https?:\/\//i, /\bfetch\s*\(\s*(?:process\.env|new\s+URL\s*\(|[A-Za-z_$][\w$]*)/,
      /\baxios\s*\.\s*(?:get|post|put|patch|delete)\s*\(/i, /\bACCOUNTSHIELD_API_URL\b/]) {
      const match = pattern.exec(file.content); if (match) { violations.push(violation("ARCH005", file.path, lineAt(file.content, match.index), "raw or dynamic backend transport detected")); break; }
    }
    if (!test && (file.path.startsWith("src/app/api/") || file.path.startsWith("src/server/bff/"))) {
      if (file.path.includes("[...")) violations.push(violation("ARCH006", file.path, 1, "catch-all API route can become a generic proxy"));
      for (const pattern of [
        /searchParams\.get\(\s*["'](?:url|path|target|destination|upstream|backend)["']/i,
        /\b(?:destination|target|upstream|backend)(?:Url|Path)\b/i,
        /\bfetch\s*\(\s*(?:url|path|target|destination|upstream|backendPath)\b/i,
        /new\s+URL\s*\(\s*(?:request|body|payload|params|searchParams)\b/i]) {
        const match = pattern.exec(file.content); if (match) { violations.push(violation("ARCH006", file.path, lineAt(file.content, match.index), "request-derived destination or path detected")); break; }
      }
    }
  }

  const envFile = resolve(projectRoot, ".env.example");
  if (existsSync(envFile)) {
    const text = await readFile(envFile, "utf8");
    for (const match of text.matchAll(/^\s*(NEXT_PUBLIC_[A-Z0-9_]+)\s*=/gm)) if (!publicEnv.has(match[1]))
      violations.push(violation("ARCH004", ".env.example", lineAt(text, match.index ?? 0), `${match[1]} is not allowlisted`));
  }

  for (const path of readOnlyClosure(graph, config.readOnlyScopes ?? [])) {
    const file = files.get(path); if (!file) continue;
    const generated = (fileImports.get(path) ?? []).find((item) => item.target?.startsWith("src/generated/") && /openapi-client\.[^.]+$/.test(item.target));
    if (generated) violations.push(violation("ARCH007", path, generated.line, "read-only scope imports a generated operation client", generated.target));
    const mutation = MUTATION.exec(file.content);
    if (mutation) violations.push(violation("ARCH007", path, lineAt(file.content, mutation.index), `read-only scope declares ${mutation[0]}`));
  }
  for (const cycle of cycles(graph)) violations.push(violation("ARCH010", cycle[0], 1, `cycle: ${cycle.join(" -> ")}`, cycle[1] ?? null));

  const exceptionState = validateExceptions(config, today);
  const final = applyExceptions([...violations, ...exceptionState.violations], exceptionState.valid);
  final.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line || a.ruleId.localeCompare(b.ruleId));
  return { files: files.size, edges: [...graph.values()].reduce((sum, targets) => sum + targets.size, 0), violations: final };
}
