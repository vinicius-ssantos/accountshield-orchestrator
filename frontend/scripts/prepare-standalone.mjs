import { access, cp, mkdir, rm } from "node:fs/promises";
import path from "node:path";

const root = process.cwd();
const standaloneRoot = path.join(root, ".next", "standalone");
const standaloneNext = path.join(standaloneRoot, ".next");

await mkdir(standaloneNext, { recursive: true });

const staticSource = path.join(root, ".next", "static");
const staticTarget = path.join(standaloneNext, "static");
await rm(staticTarget, { force: true, recursive: true });
await cp(staticSource, staticTarget, { recursive: true });

const publicSource = path.join(root, "public");
const publicTarget = path.join(standaloneRoot, "public");

try {
  await access(publicSource);
  await rm(publicTarget, { force: true, recursive: true });
  await cp(publicSource, publicTarget, { recursive: true });
} catch {
  // The console currently has no public assets. Keep the runtime minimal.
}
