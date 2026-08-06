#!/usr/bin/env node

import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import config from "../architecture.config.mjs";
import { analyzeProject } from "./architecture-analyzer.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, "..");
const jsonOutput = process.argv.includes("--json");

const result = await analyzeProject({ projectRoot, config });

if (jsonOutput) {
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} else if (result.violations.length === 0) {
  console.log(
    `architecture-check: passed (${result.files} files, ${result.edges} internal dependency edges)`,
  );
} else {
  console.error(
    `architecture-check: ${result.violations.length} violation(s) across ${result.files} files`,
  );
  for (const current of result.violations) {
    console.error(`\n${current.ruleId} ${current.file}:${current.line}`);
    console.error(`  ${current.title}`);
    console.error(`  ${current.message}`);
    if (current.target) console.error(`  target: ${current.target}`);
    console.error(`  remediation: ${current.remediation}`);
  }
}

if (result.violations.length > 0) process.exitCode = 1;
