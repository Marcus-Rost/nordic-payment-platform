# iOS codegen smoke (Swift + URLSession)

Phase 2E smoke-gate for the **iOS** client platform. Verifies that every
JRPay OpenAPI spec produces Swift types + a URLSession-backed client via
[`swift-openapi-generator`](https://github.com/apple/swift-openapi-generator)
that compile cleanly under `swift build`.

## Tooling

- `swift-openapi-generator` 1.5.x — SwiftPM plugin.
- `swift-openapi-runtime` 1.7.x — codec + `APIProtocol` base types.
- `swift-openapi-urlsession` 1.0.x — URLSession transport.
- Swift 6.x toolchain.

## Running

### Local (requires Swift toolchain)

```sh
# macOS: Xcode 16+ ships the Swift 6 toolchain.
# Linux: curl -L https://download.swift.org/swift-6.0.2-release/ubuntu2204/swift-6.0.2-RELEASE/swift-6.0.2-RELEASE-ubuntu22.04.tar.gz | tar xz
cd implementation/api-specs/codegen/ios
npm run scaffold   # Node-based: regenerates Package.swift + target dirs
swift build        # SPM plugin drives swift-openapi-generator per target
# or, combined:
npm run smoke
```

### CI

The `.github/workflows/codegen.yml` iOS job runs on a macOS runner or
inside `swift:6.0.2-noble` and executes the same commands.

## Layout

```
ios/
  package.json                     # scaffold script entry
  Package.swift                    # auto-generated — 20 spec targets
  scripts/
    scaffold.mjs                   # regenerates Package.swift + target dirs
  Sources/
    JRPayClientSmoke/              # hand-written smoke executable
      main.swift                   # imports 7 specs, uses key types
    JRPayIdentity/                 # one target per spec (auto-generated)
      openapi.yaml                 # symlink to ../../../../identity.yaml
      shared-schemas.yaml          # symlink — generator resolves cross-file
      openapi-generator-config.yaml
    JRPayCustomerAccounts/ ...     # 19 more targets, same structure
```

## What the smoke proves

- Every spec (all 20 with paths) produces Swift code that compiles
  against the production `OpenAPIRuntime` + `OpenAPIURLSession` stack.
- Cross-file `$ref` resolution works: each target's spec symlinks
  `shared-schemas.yaml` into the same directory so the generator
  resolves relative refs.
- Generated `Components.Schemas.*` enum cases are importable by name
  (the smoke file exercises `AccountStatus.active`, `PaymentStatus.settled`,
  `R2pStatus.pending`, etc.) — catches accidental naming collisions
  between specs.
- The hand-written `JRPayClientSmoke` executable depends on 7 of the
  generated targets and compiles as a single product — proves the
  targets can co-exist in one binary without symbol clashes.

## Design notes

**Per-target symlinks.** SwiftPM plugins run in each target's source
directory, so `swift-openapi-generator` needs both `openapi.yaml` and
any files it references (here, `shared-schemas.yaml`) in the same
folder. The scaffold script creates symlinks so we don't duplicate
content.

**`exclude` in Package.swift.** The generator-config YAML and the
shared-schemas symlink are listed in `exclude:` so SwiftPM doesn't try
to treat them as resources.

**Access modifier: public.** Generated types are `public` so the
`JRPayClientSmoke` executable can import them. Production client apps
would typically mark these `internal` — flip via
`openapi-generator-config.yaml` when embedding.
