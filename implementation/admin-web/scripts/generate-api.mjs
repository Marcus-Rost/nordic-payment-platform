#!/usr/bin/env node
// Generates TypeScript types from every admin-* + identity OpenAPI spec
// the Admin Web SPA consumes. The output lands in src/api/types.gen.ts as a
// single namespace per spec (so call sites stay specific:
// `paths['/api/v1/admin/fraud/cases']['get']`).
//
// This is a tiny wrapper around openapi-typescript that runs against the
// in-tree specs at ../api-specs/. The CI codegen smoke (api-specs/codegen/web)
// independently verifies every spec compiles.

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const SPECS = resolve(ROOT, '../api-specs');
const OUT = resolve(ROOT, 'src/api/types.gen.ts');

const targets = [
    ['identity', 'identity.yaml'],
    ['adminAccounts', 'admin-accounts.yaml'],
    ['adminFraud', 'admin-fraud.yaml'],
    ['adminSanctions', 'admin-sanctions.yaml'],
    ['adminAudit', 'admin-audit.yaml'],
    ['adminPayments', 'admin-payments.yaml'],
    ['adminReconciliation', 'admin-reconciliation.yaml'],
    ['adminGdpr', 'admin-gdpr.yaml'],
    ['adminStatus', 'admin-status.yaml'],
    ['adminUsers', 'admin-users.yaml'],
];

mkdirSync(dirname(OUT), { recursive: true });

const blocks = [];
blocks.push('// AUTO-GENERATED — do not edit by hand.');
blocks.push('// Regenerate with: npm run generate-api');
blocks.push('// Source specs: implementation/api-specs/*.yaml');
blocks.push('');

for (const [namespace, file] of targets) {
    const specPath = resolve(SPECS, file);
    process.stdout.write(`generating ${namespace} from ${file} ... `);
    const ast = await openapiTS(new URL(`file://${specPath}`));
    const ts = astToString(ast);
    blocks.push(`// =====================================================`);
    blocks.push(`// ${file}`);
    blocks.push(`// =====================================================`);
    blocks.push(`export namespace ${namespace} {`);
    // openapiTS emits top-level `export interface paths`, etc.
    // Indent into the namespace and strip the `export` keyword (members of
    // a namespace are already exported via the namespace itself).
    blocks.push(ts
        .split('\n')
        .map((l) => '    ' + l.replace(/^export /, ''))
        .join('\n'));
    blocks.push('}');
    blocks.push('');
    process.stdout.write('ok\n');
}

writeFileSync(OUT, blocks.join('\n'));
console.log(`\nWrote ${OUT}`);
