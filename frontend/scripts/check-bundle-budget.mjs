#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import config from "../performance-budget.config.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, "..");
const statsPath = resolve(projectRoot, ".next/diagnostics/route-bundle-stats.json");

let stats;
try {
  stats = JSON.parse(readFileSync(statsPath, "utf8"));
} catch (error) {
  console.error(`budget-check: could not read ${statsPath} -- run "npm run build" first.`);
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}

if (!Array.isArray(stats) || stats.length === 0) {
  console.error(`budget-check: ${statsPath} did not contain any route bundle stats.`);
  process.exit(1);
}

const budgetBytes = config.maxFirstLoadUncompressedJsBytes;
if (!Number.isFinite(budgetBytes) || budgetBytes <= 0) {
  console.error("budget-check: performance-budget.config.mjs's maxFirstLoadUncompressedJsBytes is invalid.");
  process.exit(1);
}

const budgetKb = Math.round(budgetBytes / 1024);
let violationCount = 0;

for (const route of stats) {
  const bytes = route.firstLoadUncompressedJsBytes;
  if (typeof bytes !== "number" || !Number.isFinite(bytes)) {
    console.error(`budget-check: route "${route.route}" is missing firstLoadUncompressedJsBytes.`);
    process.exit(1);
  }
  const kb = Math.round(bytes / 1024);
  const overBudget = bytes > budgetBytes;
  if (overBudget) violationCount += 1;
  console.log(`${overBudget ? "FAIL" : "pass"} ${route.route} ${kb}KB / ${budgetKb}KB`);
}

if (violationCount > 0) {
  console.error(`\nbudget-check: ${violationCount} route(s) exceeded the ${budgetKb}KB first-load JS budget.`);
  process.exit(1);
}

console.log(`\nbudget-check: passed (${stats.length} routes, budget ${budgetKb}KB)`);
