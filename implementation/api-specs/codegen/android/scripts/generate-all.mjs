// Run openapi-generator-cli with the Kotlin client template over every
// JRPay spec. Output lands in `generated/<spec>/` and is compiled by a
// Gradle multi-project build (see ../build.gradle.kts + settings.gradle.kts).
//
// We use the `kotlin` generator with `library=jvm-retrofit2` — the same
// library a production Android client would use. The smoke verifies
// Retrofit interface generation + data-class models + enum mapping.

import { readdir, mkdir, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const thisFile = fileURLToPath(import.meta.url);
const androidDir = resolve(dirname(thisFile), "..");
const specsDir = resolve(androidDir, "../..");
const outRoot = resolve(androidDir, "generated");

const SKIP = new Set(["shared-schemas.yaml"]); // component library — no paths

function sanitise(name) {
  return name.replace(/\.yaml$/, "").replace(/-/g, "_");
}

function packageNameFor(moduleName) {
  return `io.jrpay.codegen.${moduleName}`;
}

async function main() {
  if (existsSync(outRoot)) {
    await rm(outRoot, { recursive: true, force: true });
  }
  await mkdir(outRoot, { recursive: true });

  const files = (await readdir(specsDir))
    .filter((f) => f.endsWith(".yaml"))
    .filter((f) => !f.startsWith("."))
    .filter((f) => !SKIP.has(f))
    .sort();

  let failed = 0;
  const modules = [];
  for (const file of files) {
    const moduleName = sanitise(file);
    const source = join(specsDir, file);
    const moduleDir = join(outRoot, moduleName);
    process.stdout.write(`Generating ${moduleName}... `);
    const result = spawnSync(
      "npx",
      [
        "--yes",
        "@openapitools/openapi-generator-cli@2.13.4",
        "generate",
        "-i",
        source,
        "-g",
        "kotlin",
        "--library",
        "jvm-retrofit2",
        "-o",
        moduleDir,
        "--package-name",
        packageNameFor(moduleName),
        "--additional-properties",
        [
          "useCoroutines=true",
          // moshi handles free-form `Map<String, Any>` that our RFC 9457
          // Problem.details field uses; kotlinx_serialization trips on
          // `kotlin.Any` which has no built-in serializer.
          "serializationLibrary=moshi",
          "moshiCodeGen=true",
          "enumPropertyNaming=UPPERCASE",
          "collectionType=list",
          "groupId=io.jrpay.codegen",
          `artifactId=${moduleName}`,
          "nullableReturnType=true",
          "omitGradleWrapper=true",
          "skipDefaultInterface=true",
        ].join(","),
        "--global-property",
        [
          "modelTests=false",
          "modelDocs=false",
          "apiTests=false",
          "apiDocs=false",
          "skipFormModel=true",
        ].join(","),
      ],
      { cwd: androidDir, stdio: ["ignore", "pipe", "pipe"], encoding: "utf8" },
    );
    if (result.status === 0) {
      console.log("OK");
      modules.push(moduleName);
    } else {
      console.log("FAIL");
      console.error(result.stderr || result.stdout);
      failed += 1;
    }
  }

  if (failed === 0) {
    // Post-process each generated module:
    //   - remove the Groovy `build.gradle` (clashes with our Kotlin-DSL
    //     root build) and other per-module tooling boilerplate;
    //   - strip the Apache-Oltu-based OAuth helpers from `auth/` and
    //     the oltu-coupled `ApiClient.kt` / `OAuthOkHttpClient.kt`.
    //     The retrofit interfaces only need `CollectionFormats.kt` plus
    //     the serialization adapters.
    const PACKAGE_PATH = (m) => join(
      outRoot, m, "src/main/kotlin/io/jrpay/codegen", m,
    );
    for (const m of modules) {
      for (const noise of ["build.gradle", "settings.gradle", "docs", "README.md", "proguard-rules.pro"]) {
        const p = join(outRoot, m, noise);
        if (existsSync(p)) {
          await rm(p, { recursive: true, force: true });
        }
      }
      const pkgDir = PACKAGE_PATH(m);
      // Drop the whole auth/ directory — OAuth/ApiKey helpers built
      // against Apache Oltu aren't part of our smoke scope.
      const authDir = join(pkgDir, "auth");
      if (existsSync(authDir)) await rm(authDir, { recursive: true, force: true });
      // Drop infra files that reference org.apache.oltu or pull in a
      // full runtime client. Keep CollectionFormats, Serializer, and
      // the date/time adapters — the API interfaces depend on them.
      const infraDir = join(pkgDir, "infrastructure");
      if (existsSync(infraDir)) {
        for (const f of await readdir(infraDir)) {
          const drop = [
            "ApiClient.kt",
            "OAuthOkHttpClient.kt",
            "RequestConfig.kt",
            "ResponseExt.kt",
            "ApiInfrastructureResponse.kt",
            "Errors.kt",
            "ApiAbstractions.kt",
            "HttpResponse.kt",
            "PartConfig.kt",
            "RequestMethod.kt",
          ];
          if (drop.includes(f)) {
            await rm(join(infraDir, f), { force: true });
          }
        }
      }
    }

    // Update Gradle settings with the list of generated subprojects so
    // `gradle compileKotlin` picks them all up without manual editing.
    const settings =
      "rootProject.name = \"jrpay-android-codegen-smoke\"\n\n" +
      modules
        .map(
          (m) => `include(":${m}")\nproject(":${m}").projectDir = file("generated/${m}")\n`,
        )
        .join("") +
      "\n";
    await writeFile(join(androidDir, "settings.gradle.kts"), settings, "utf8");
    console.log(`\nGenerated ${modules.length} modules; settings.gradle.kts refreshed.`);
    process.exit(0);
  } else {
    console.error(`\n${failed} module(s) failed to generate.`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
