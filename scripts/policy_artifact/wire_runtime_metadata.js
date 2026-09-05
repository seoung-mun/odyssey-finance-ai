#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "../..");
const data = path.join(root, "data", "policy");
const sourcePath = path.join(data, "odyssey_policy_51_current_schema_handoff_v3.json");
const statusPath = path.join(
  data,
  "odyssey_policy_51_application_status_asof_2026-09-01_v3.json",
);
const eligibilityPath = path.join(data, "odyssey_policy_51_region_age_companion.json");
const readyPath = path.join(data, "odyssey_policy_51_ready_to_connect.json");
const outputPath = path.join(data, "odyssey_policy_51_runtime_metadata_handoff_v1.json");

function load(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function byPolicyKey(document, label) {
  if (document.policyCount !== 51 || document.policies.length !== 51) {
    throw new Error(`${label} must contain exactly 51 policies`);
  }
  const result = new Map();
  for (const policy of document.policies) {
    if (!policy.policyKey || result.has(policy.policyKey)) {
      throw new Error(`${label} contains a missing or duplicate policyKey`);
    }
    result.set(policy.policyKey, policy);
  }
  return result;
}

function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function assertSameIdentity(policy, reference, label) {
  if (
    !reference ||
    policy.title !== reference.title ||
    policy.supportGoal !== reference.supportGoal
  ) {
    throw new Error(`${label} identity mismatch for ${policy.policyKey}`);
  }
}

let raw = fs.readFileSync(sourcePath, "utf8").replace(/\r?\n$/, "");
const handoff = JSON.parse(raw);
if (handoff.policies.length !== 51 || new Set(handoff.policies.map((p) => p.policyKey)).size !== 51) {
  throw new Error("handoff must contain 51 unique policyKey values");
}

const statusDocument = load(statusPath);
const statusByKey = byPolicyKey(statusDocument, "application status reference");
const eligibilityDocument = load(eligibilityPath);
const eligibilityByKey = byPolicyKey(eligibilityDocument, "region/age companion");
const readyDocument = load(readyPath);
const readyByKey = byPolicyKey(readyDocument, "ready-to-connect reference");

const applicationStatuses = [];
const eligibilities = [];
for (const policy of handoff.policies) {
  const status = statusByKey.get(policy.policyKey);
  const companion = eligibilityByKey.get(policy.policyKey);
  const ready = readyByKey.get(policy.policyKey);
  assertSameIdentity(policy, status, "application status");
  assertSameIdentity(policy, companion, "region/age companion");
  assertSameIdentity(policy, ready, "ready-to-connect");

  const eligibility = {
    ageMax: companion.age.maxCandidate,
    ageMin: companion.age.minCandidate,
    regionCodes: companion.region.regionCodes,
    regionScope: companion.region.scopeHint,
  };
  if (stableStringify(eligibility) !== stableStringify(ready.eligibility)) {
    throw new Error(`eligibility references disagree for ${policy.policyKey}`);
  }
  applicationStatuses.push({
    asOfDate: statusDocument.asOfDate,
    currentStatus: status.currentStatus,
    decision: status.top3Decision,
    evidenceUrl: status.evidenceUrl,
    verifiedAt: status.verifiedAt,
    verifiedVia: status.verifiedVia,
  });
  eligibilities.push(eligibility);
}

if (handoff.policies.some((policy) => policy.version.reviewStatus !== "PENDING")) {
  throw new Error("handoff review status must remain PENDING");
}
if (
  handoff.policies.some(
    (policy) =>
      !["ELIGIBILITY_ONLY", "INFORMATIONAL"].includes(policy.version.calculationMode) ||
      policy.version.calculationRule !== null,
  )
) {
  throw new Error("runtime metadata wiring must not activate calculation rules");
}

let versionIndex = 0;
raw = raw.replace(/"version":\{/g, (match) => {
  const status = applicationStatuses[versionIndex++];
  return `${match}"applicationStatus":${stableStringify(status)},`;
});
if (versionIndex !== 51) {
  throw new Error(`expected 51 version objects, found ${versionIndex}`);
}

let eligibilityIndex = 0;
raw = raw.replace(/"effectiveTo":(null|"[^"]*"),/g, (match) => {
  const eligibility = eligibilities[eligibilityIndex++];
  return `${match}"eligibility":${stableStringify(eligibility)},`;
});
if (eligibilityIndex !== 51) {
  throw new Error(`expected 51 effectiveTo fields, found ${eligibilityIndex}`);
}

raw = raw.replace(
  '"artifactVersion":"handoff-51-current-schema-v3-2026-09-01"',
  '"artifactVersion":"handoff-51-runtime-metadata-v1-2026-09-01"',
);
raw = raw.replace(/"manifestSha256":"[0-9a-f]{64}"/, '"manifestSha256":""');
const manifest = crypto.createHash("sha256").update(Buffer.from(raw, "utf8")).digest("hex");
raw = raw.replace('"manifestSha256":""', `"manifestSha256":"${manifest}"`);

const output = JSON.parse(raw);
const counts = output.policies.reduce((result, policy) => {
  const decision = policy.version.applicationStatus.decision;
  result[decision] = (result[decision] || 0) + 1;
  return result;
}, {});
if (counts.ALLOW !== 23 || counts.EXCLUDE !== 11 || counts.RECHECK !== 17) {
  throw new Error(`unexpected application status counts: ${JSON.stringify(counts)}`);
}
if (output.reviewGate.status !== "PENDING" || output.reviewGate.importable !== false) {
  throw new Error("review gate must remain PENDING and non-importable");
}

fs.writeFileSync(outputPath, `${raw}\n`, "utf8");
console.log(
  JSON.stringify({
    output: path.relative(root, outputPath),
    policies: output.policies.length,
    ...counts,
    bytes: fs.statSync(outputPath).size,
    manifestSha256: manifest,
  }),
);
