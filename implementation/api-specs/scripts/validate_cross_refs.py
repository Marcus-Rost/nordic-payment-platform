#!/usr/bin/env python3
"""Cross-file OpenAPI 3.1 validator for JRPay specs.

Spectral cannot resolve `$ref` values that point to sibling files under the
`shared-schemas.yaml#/components/...` convention — so a standalone
cross-file validator runs alongside the Spectral lint in CI. This script
uses `prance` as the resolver (wraps multi-file refs correctly) and
`openapi-spec-validator` as the schema checker.

Exit code: 0 if every spec under `implementation/api-specs/` validates, 1
otherwise. Per-file status is printed to stdout.
"""
from __future__ import annotations

import sys
import warnings
from pathlib import Path

try:
    from prance import ResolvingParser
except ImportError as exc:  # pragma: no cover - guard for missing deps
    print(
        "prance is not installed. Run `pip install -r scripts/requirements.txt`.",
        file=sys.stderr,
    )
    raise SystemExit(1) from exc


SPEC_DIR = Path(__file__).resolve().parent.parent
SKIP_FILES = set()  # Reserved for future per-file exemptions.


def spec_files(base: Path) -> list[Path]:
    # `.spectral.yaml` and other dotfiles live alongside the specs but
    # are tooling config — not OpenAPI documents. Filter them out.
    return sorted(
        p
        for p in base.glob("*.yaml")
        if not p.name.startswith(".") and p.name not in SKIP_FILES
    )


def validate(path: Path) -> tuple[bool, str]:
    try:
        ResolvingParser(
            str(path),
            strict=False,
            backend="openapi-spec-validator",
            lazy=False,
        )
        return True, ""
    except Exception as exc:  # noqa: BLE001 - prance raises many types
        msg = str(exc).strip().splitlines()[0]
        # Prance wraps validator errors; 500 chars covers deeply nested ones.
        return False, msg[:500]


def main() -> int:
    warnings.filterwarnings("ignore")
    files = spec_files(SPEC_DIR)
    if not files:
        print(f"No YAML specs found under {SPEC_DIR}", file=sys.stderr)
        return 1
    failures: list[Path] = []
    for path in files:
        ok, err = validate(path)
        status = "OK" if ok else "FAIL"
        suffix = f"  -> {err}" if err else ""
        print(f"{status}  {path.name}{suffix}")
        if not ok:
            failures.append(path)
    if failures:
        print(
            f"\n{len(failures)} / {len(files)} specs failed validation.",
            file=sys.stderr,
        )
        return 1
    print(f"\nAll {len(files)} specs valid.")
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
