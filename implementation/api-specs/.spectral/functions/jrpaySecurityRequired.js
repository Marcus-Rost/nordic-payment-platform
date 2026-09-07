// Spectral custom function: jrpaySecurityRequired
//
// Invoked per Operation Object. Asserts that authenticated operations
// carry a `security:` block (overriding or inheriting). Exemptions:
//
//   - `/oauth2/**` — OAuth2 endpoints are the identity boundary itself.
//   - `/.well-known/**` — OIDC discovery (public by spec).
//   - `/api/v1/public/**` — explicitly public surface (e.g. status banner).
//   - SSE stream endpoints authenticated via a one-time stream token in
//     the query string (`SSE_CONVENTIONS.md` §"Authenticated streams").
//     Detected structurally: path ends in `/stream` AND a `token` query
//     parameter is declared on the operation.

const EXEMPT_PATH_PREFIXES = [
  "/oauth2/",
  "/.well-known/",
  "/api/v1/public/",
];

function isExemptByPrefix(pathKey) {
  if (typeof pathKey !== "string") return false;
  return EXEMPT_PATH_PREFIXES.some((prefix) => pathKey.startsWith(prefix));
}

function isStreamTokenAuthed(pathKey, targetVal) {
  if (typeof pathKey !== "string") return false;
  if (!pathKey.endsWith("/stream")) return false;
  if (!targetVal || !Array.isArray(targetVal.parameters)) return false;
  return targetVal.parameters.some(
    (p) => p && p.name === "token" && p.in === "query" && p.required === true,
  );
}

export default function jrpaySecurityRequired(targetVal, _options, context) {
  if (!targetVal || typeof targetVal !== "object") return;
  const path = context && context.path;
  if (!Array.isArray(path) || path.length < 3) return;
  const pathKey = path[path.length - 2];
  if (isExemptByPrefix(pathKey)) return;
  if (isStreamTokenAuthed(pathKey, targetVal)) return;
  if (Array.isArray(targetVal.security) && targetVal.security.length > 0) return;
  return [
    {
      message: `Operation ${pathKey} is missing a security declaration (convention GP-SEC-REQUIRED)`,
    },
  ];
}
