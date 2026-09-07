# Web codegen smoke (TypeScript)

Phase 2E smoke-gate for the **web** client platform. Verifies that every
JRPay OpenAPI spec produces TypeScript types that compile under the
same strict settings a real web app would use.

## Tooling

- [`openapi-typescript`](https://openapi-ts.dev/) generates fully-typed
  `paths` + `components` interfaces.
- `typescript` (`tsc --noEmit --strict`) is the compile gate.

Both are pinned in `package.json`.

## Running locally

```sh
cd implementation/api-specs/codegen/web
npm install
npm run smoke    # generate + typecheck in one go
```

Individual phases:

```sh
npm run generate   # writes generated/*.ts from every ../../*.yaml
npm run typecheck  # tsc --noEmit against src + generated
```

## Layout

```
web/
  package.json                # tooling deps + scripts
  tsconfig.json               # strict, bundler module resolution
  scripts/
    generate-all.mjs          # walks the spec folder, emits per-spec .ts
  src/
    smoke.ts                  # imports types from 7 specs + exercises
                              # discriminated unions + required props
  generated/                  # .gitignore'd — written by the generator
```

## What the smoke proves

- Every spec (all 21) produces syntactically valid TypeScript.
- `paths` indexing works (`paths["/foo"]["get"]["responses"][200]`).
- Generated `components` union types are assignable.
- Request-body content-types are carried through correctly.
- Enum types are string-literal unions (not `string`) — catches the
  failure mode where a generator collapses enums silently.

## CI

`.github/workflows/codegen.yml` runs `npm run smoke` on every PR that
changes any spec or the `.spectral.yaml` ruleset.
