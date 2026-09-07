// Spectral custom function: jrpayIdempotencyKeyRequired
//
// Invoked per Operation Object for POST / PATCH / DELETE verbs. Passes if
// the operation declares the `Idempotency-Key` header parameter, either
// inline (`name: "Idempotency-Key"`) or via a $ref ending in
// `/parameters/IdempotencyKey`.
//
// Exemptions:
//   - Paths under `/oauth2/**` use RFC-defined idempotency models.
//   - Paths under `/.well-known/**` are not mutating.
//   - Public status endpoints `/api/v1/public/**` do not accept customer
//     idempotency keys (rate-limited per IP instead).
//
// The `given` JSONPath targets operations only; the caller provides the
// `path` of the operation via Spectral's context so we can peek at the
// parent path name.

const EXEMPT_PATH_PREFIXES = [
  "/oauth2/",
  "/.well-known/",
  "/api/v1/public/",
];

function isExempt(pathKey) {
  if (typeof pathKey !== "string") return false;
  return EXEMPT_PATH_PREFIXES.some((prefix) => pathKey.startsWith(prefix));
}

function hasIdempotencyKeyParam(parameters) {
  if (!Array.isArray(parameters)) return false;
  return parameters.some((p) => {
    if (!p) return false;
    if (p.name === "Idempotency-Key" && p.in === "header") return true;
    if (typeof p.$ref === "string" && p.$ref.endsWith("/parameters/IdempotencyKey")) return true;
    return false;
  });
}

export default function jrpayIdempotencyKeyRequired(targetVal, _options, context) {
  if (!targetVal || typeof targetVal !== "object") return;
  // context.path is an array like ["paths", "/api/v1/.../foo", "post"].
  const path = context && context.path;
  if (!Array.isArray(path) || path.length < 3) return;
  const pathKey = path[path.length - 2];
  const verb = path[path.length - 1];
  if (!["post", "patch", "delete"].includes(verb)) return;
  if (isExempt(pathKey)) return;
  if (hasIdempotencyKeyParam(targetVal.parameters)) return;
  return [
    {
      message: `Mutating ${verb.toUpperCase()} ${pathKey} is missing an Idempotency-Key header parameter (convention GP-IDEMPOTENCY)`,
    },
  ];
}
