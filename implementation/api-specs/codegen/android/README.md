# Android codegen smoke (Kotlin + Retrofit)

Phase 2E smoke-gate for the **Android** client platform. Verifies that
every JRPay OpenAPI spec produces Kotlin client code that compiles
against Retrofit 2 + Moshi.

## Tooling

- [`@openapitools/openapi-generator-cli`](https://openapi-generator.tech/)
  (npm wrapper around the Java-based generator) with the `kotlin`
  generator and `--library jvm-retrofit2`.
- Gradle 8.12 (installed via SDKMAN — see `../../BUILD_GUIDE.md`).
- Kotlin 2.0.21 with JVM toolchain 21.
- Moshi for JSON codec (handles `Map<String, Any>` in RFC 9457
  Problem.details where kotlinx.serialization stumbles).

## Running locally

Prereqs: `sdk install gradle 8.12 && sdk install kotlin 2.0.21` +
Node 20.

```sh
cd implementation/api-specs/codegen/android
npm install
npm run smoke     # generate + gradle compileKotlin
```

Individual phases:

```sh
npm run generate   # writes generated/<spec>/... per spec (20 modules)
npm run compile    # gradle compileKotlin across all 20 modules
```

## Layout

```
android/
  package.json                     # wraps openapi-generator-cli
  build.gradle.kts                 # root multi-project build
  settings.gradle.kts              # auto-regenerated per module
  gradle.properties                # parallel + caching tuning
  scripts/
    generate-all.mjs               # per-spec invocation + post-process
  generated/                       # .gitignore'd — 20 Kotlin subprojects
```

## What the smoke proves

- Every spec (all 20 with paths) produces Kotlin code that
  round-trips through the production Retrofit 2 + Moshi stack.
- Polymorphic types (`oneOf` / `discriminator`) compile —
  `ConsentSummary` / `ConsentDetail` have been structured to avoid a
  known generator quirk with `allOf`-inherited $ref properties.
- Free-form `Map<String, Any>` types (`Problem.details`) compile —
  Moshi handles them where kotlinx-serialization fails at build time.
- Suspend functions (`useCoroutines=true`) compile against Retrofit's
  coroutine support.
- Enum naming (`UPPERCASE` via `enumPropertyNaming`) produces valid
  Kotlin identifiers for every value in every spec enum.

## Design notes

**Post-processing.** The generator emits OAuth helper classes from
Apache Oltu (`OAuth.kt`, `OAuthOkHttpClient.kt`) and an `ApiClient.kt`
runtime coupled to them. These reference libraries we don't need for a
smoke; the generate script strips them after each run.

**Per-module build files.** The generator emits a Groovy
`build.gradle` per module that conflicts with our root Kotlin-DSL
build. The generate script removes them so `subprojects { ... }` is
authoritative.

**settings.gradle.kts is regenerated.** Running `npm run generate`
rewrites `settings.gradle.kts` with the current set of modules; manual
edits are overwritten. The checked-in version after generation lists
all 20 modules.

## CI

`.github/workflows/codegen.yml` runs `npm run smoke` on every PR that
touches any spec or shared architecture doc (`PROBLEM_TYPES.md`,
`SHARED_ENUMS.md`).
