import { readFile, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";

execFileSync(
  process.execPath,
  [
    "node_modules/openapi-typescript/bin/cli.js",
    "../contracts/backend.openapi.yaml",
    "-o",
    "src/shared/api/schema.ts",
  ],
  { stdio: "inherit" },
);
const path = "src/shared/api/schema.ts";
const source = await readFile(path, "utf8");
await writeFile(path, source.replace(/\/\*\*[\s\S]*?\*\//g, ""));
