// Spectral custom function: jrpayProblemTypesRegistered
//
// Scans a string value for any `urn:problem:<slug>` tokens and asserts that
// every match appears in the registry supplied via functionOptions.registry
// (source of truth: gui/architecture/PROBLEM_TYPES.md).
//
// Used to enforce that every Problem-type URI referenced in spec prose (op
// descriptions, response descriptions, examples) is one the GUI tier has
// actually registered. An unregistered URN means the customer / operator
// GUI has no copy for it and the error would render as the generic
// "unexpected-error".

const URN_REGEX = /urn:problem:[a-z0-9][a-z0-9-]*/g;

export default function jrpayProblemTypesRegistered(targetVal, options) {
  if (typeof targetVal !== "string" || targetVal.length === 0) return;
  const registry = (options && options.registry) || [];
  if (registry.length === 0) {
    return [
      {
        message:
          "jrpayProblemTypesRegistered: registry not configured; see PROBLEM_TYPES.md",
      },
    ];
  }
  const registrySet = new Set(registry);
  const results = [];
  const seen = new Set();
  let match;
  while ((match = URN_REGEX.exec(targetVal)) !== null) {
    const urn = match[0];
    if (seen.has(urn)) continue;
    seen.add(urn);
    if (!registrySet.has(urn)) {
      results.push({
        message: `Problem type "${urn}" is not registered in gui/architecture/PROBLEM_TYPES.md`,
      });
    }
  }
  return results;
}
