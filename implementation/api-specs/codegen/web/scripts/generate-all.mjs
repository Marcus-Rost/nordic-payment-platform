// Generate TypeScript types for every JRPay OpenAPI spec into
// `generated/<name>.ts`. Intended to run as part of the Phase 2E smoke:
// types must be valid TypeScript that `tsc --strict --noEmit` accepts.
//
// We use `openapi-typescript`'s programmatic API so we can pass an
// absolute source path. The CLI would also work but would inline
// filesystem walking here anyway.

import { readdir, mkdir, writeFile, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import openapiTS, { astToString } from "openapi-typescript";

const thisFile = fileURLToPath(import.meta.url);
const webDir = resolve(dirname(thisFile), "..");
const specsDir = resolve(webDir, "../..");
const outDir = resolve(webDir, "generated");

const SKIP = new Set(["shared-schemas.yaml"]); // pathless component library

async function main() {
  if (existsSync(outDir)) {
    await rm(outDir, { recursive: true, force: true });
  }
  await mkdir(outDir, { recursive: true });

  const files = (await readdir(specsDir))
    .filter((f) => f.endsWith(".yaml"))
    .filter((f) => !f.startsWith("."))
    .filter((f) => !SKIP.has(f))
    .sort();

  let exitCode = 0;
  for (const file of files) {
    const source = join(specsDir, file);
    const outName = file.replace(/\.yaml$/, ".ts");
    const outPath = join(outDir, outName);
    process.stdout.write(`Generating ${outName}... `);
    try {
      const ast = await openapiTS(new URL(`file://${source}`));
      const ts = astToString(ast);
      await writeFile(outPath, ts, "utf8");
      console.log("OK");
    } catch (err) {
      console.log("FAIL");
      console.error(err instanceof Error ? err.message : err);
      exitCode = 1;
    }
  }

  console.log(`\nGenerated ${files.length} files into generated/`);
  process.exit(exitCode);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
