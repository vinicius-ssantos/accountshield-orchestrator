#!/usr/bin/env node

import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";

import { analyzeProject } from "./architecture-analyzer.mjs";

const baseConfig = {
  publicEnvAllowlist: ["NEXT_PUBLIC_APP_ENV"],
  generatedImportAllowedPrefixes: ["src/server/bff/"],
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
    "src/features/decisions/",
  ],
  exceptions: [],
};

async function writeProject(root, files) {
  for (const [path, content] of Object.entries(files)) {
    const target = resolve(root, path);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, content, "utf8");
  }
}

async function analyze(files, config = baseConfig) {
  const root = await mkdtemp(resolve(tmpdir(), "accountshield-arch-"));
  try {
    await mkdir(resolve(root, "src"), { recursive: true });
    await writeProject(root, files);
    return await analyzeProject({
      projectRoot: root,
      config,
      today: "2026-07-27",
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function expectClean(name, files) {
  const result = await analyze(files);
  if (result.violations.length > 0) {
    throw new Error(
      `${name}: expected no violations, received ${result.violations
        .map((item) => `${item.ruleId}:${item.file}`)
        .join(", ")}`,
    );
  }
  console.log(`PASS ${name}`);
}

async function expectRule(name, ruleId, files, config = baseConfig) {
  const result = await analyze(files, config);
  if (!result.violations.some((item) => item.ruleId === ruleId)) {
    throw new Error(
      `${name}: expected ${ruleId}, received ${result.violations
        .map((item) => item.ruleId)
        .join(", ") || "no violations"}`,
    );
  }
  console.log(`PASS ${name} -> ${ruleId}`);
}

await expectClean("compliant dependency graph", {
  "src/app/page.tsx": 'import { getDecisionsDataSource } from "@/features/decisions/get-data-source";\nexport default async function Page() { return (await getDecisionsDataSource().list()).length; }\n',
  "src/features/decisions/data-source.ts": "export interface DecisionsDataSource { list(): Promise<readonly string[]> }\n",
  "src/features/decisions/fixtures.ts": 'import type { DecisionsDataSource } from "./data-source";\nexport const fixture: DecisionsDataSource = { async list() { return []; } };\n',
  "src/features/decisions/get-data-source.ts": 'import { fixture } from "./fixtures";\nexport function getDecisionsDataSource() { return fixture; }\n',
  "src/server/bff/adapter.ts": 'import "server-only";\nimport type { Model } from "@/generated/model";\nexport type View = Pick<Model, "id">;\n',
  "src/generated/model.ts": "export interface Model { readonly id: string }\n",
  "src/app/api/bff/runtime-status/route.ts": 'import { readStatus } from "@/server/bff/runtime-status";\nexport function GET() { return readStatus(); }\n',
  "src/server/bff/runtime-status.ts": 'import "server-only";\nexport function readStatus() { return { status: "UP" }; }\n',
  ".env.example": "NEXT_PUBLIC_APP_ENV=local\n",
});

await expectRule("client/server boundary", "ARCH001", {
  "src/app/client.tsx": '"use client";\nimport { secret } from "@/server/secret";\nexport const value = secret;\n',
  "src/server/secret.ts": 'import "server-only";\nexport const secret = "hidden";\n',
});

await expectRule("generated import boundary", "ARCH002", {
  "src/features/alpha/view.ts": 'import type { Model } from "@/generated/model";\nexport type View = Model;\n',
  "src/generated/model.ts": "export interface Model { id: string }\n",
});

await expectRule("fixture bypass", "ARCH003", {
  "src/app/page.ts": 'import { fixture } from "@/features/alpha/fixtures";\nexport const value = fixture;\n',
  "src/features/alpha/fixtures.ts": "export const fixture = [];\n",
});

await expectRule("public environment allowlist", "ARCH004", {
  "src/app/page.ts": "export const value = process.env.NEXT_PUBLIC_SECRET;\n",
});

await expectRule("raw backend access", "ARCH005", {
  "src/features/alpha/view.ts": 'export async function load() { return fetch("https://backend.invalid/api"); }\n',
});

await expectRule("generic BFF proxy", "ARCH006", {
  "src/app/api/bff/[...path]/route.ts": 'export async function GET(request) { const path = new URL(request.url).searchParams.get("path"); return fetch(path); }\n',
});

await expectRule("request-derived proxy destination", "ARCH006", {
  "src/app/api/bff/proxy/route.ts": 'export async function GET(request) { return fetch(new URL(request.url)); }\n',
});

await expectRule("read-only mutation", "ARCH007", {
  "src/features/decisions/service.ts": 'import { create } from "@/generated/openapi-client";\nexport const submit = create;\n',
  "src/generated/openapi-client.ts": 'export function create(transport) { return transport.request({ method: "POST", path: "/decisions" }); }\n',
});

await expectRule("forbidden layer direction", "ARCH008", {
  "src/design-system/button.ts": 'import { decision } from "@/features/alpha/model";\nexport const Button = decision;\n',
  "src/features/alpha/model.ts": "export const decision = {};\n",
});

await expectRule("cross-feature import", "ARCH009", {
  "src/features/alpha/service.ts": 'import { beta } from "@/features/beta/model";\nexport const alpha = beta;\n',
  "src/features/beta/model.ts": "export const beta = {};\n",
});

await expectRule("dependency cycle", "ARCH010", {
  "src/features/alpha/a.ts": 'import { b } from "./b";\nexport const a = b;\n',
  "src/features/alpha/b.ts": 'import { a } from "./a";\nexport const b = a;\n',
});

await expectRule(
  "invalid exception policy",
  "ARCH011",
  { "src/app/page.ts": "export const page = true;\n" },
  {
    ...baseConfig,
    exceptions: [
      {
        ruleId: "ARCH001",
        path: "src/app/*",
        rationale: "too broad",
        owner: "frontend",
        issue: "#77",
        revisitOn: "2026-07-26",
      },
    ],
  },
);

console.log("architecture-rule-tests: all scenarios passed");
