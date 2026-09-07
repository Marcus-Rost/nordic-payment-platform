// Spectral custom function: jrpayRfc9457ErrorFormat
//
// Invoked per Operation Object. For every 4xx / 5xx response declared on
// the operation, asserts that the response either:
//   - Uses a $ref to a shared response in shared-schemas.yaml (those are
//     pre-validated to carry `application/problem+json`), OR
//   - Declares `content["application/problem+json"]` directly.
//
// Uses RFC 9457 Problem Details as the canonical error contract for the
// JRPay GUI tier. OAuth2 endpoints are exempt — RFC 6749 specifies a
// non-Problem error format (`{error, error_description, error_uri}`).

const EXEMPT_PATH_PREFIXES = ["/oauth2/"];
const SHARED_PROBLEM_RESPONSE_REF = "shared-schemas.yaml#/components/responses/";

function isExemptByPrefix(pathKey) {
  if (typeof pathKey !== "string") return false;
  return EXEMPT_PATH_PREFIXES.some((prefix) => pathKey.startsWith(prefix));
}

function isProblemResponse(response) {
  if (!response || typeof response !== "object") return false;
  if (typeof response.$ref === "string" && response.$ref.startsWith(SHARED_PROBLEM_RESPONSE_REF)) {
    return true;
  }
  if (response.content && response.content["application/problem+json"]) return true;
  return false;
}

export default function jrpayRfc9457ErrorFormat(targetVal, _options, context) {
  if (!targetVal || typeof targetVal !== "object") return;
  const path = context && context.path;
  if (!Array.isArray(path) || path.length < 3) return;
  const pathKey = path[path.length - 2];
  if (isExemptByPrefix(pathKey)) return;
  const responses = targetVal.responses;
  if (!responses || typeof responses !== "object") return;
  const problems = [];
  for (const [statusKey, response] of Object.entries(responses)) {
    if (!/^[45][0-9]{2}$/.test(statusKey)) continue;
    if (isProblemResponse(response)) continue;
    problems.push({
      message: `${pathKey} response ${statusKey} does not use application/problem+json (RFC 9457)`,
      path: ["responses", statusKey],
    });
  }
  return problems;
}
