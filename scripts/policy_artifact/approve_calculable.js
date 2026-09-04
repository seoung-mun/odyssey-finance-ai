#!/usr/bin/env node
"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "../..");
const basePath = path.join(root, "data/policy/policy-artifact-informational-approved-23.json");
const candidatesPath = path.join(root, "data/policy/policy-calculable-allow-11-approval-candidates.json");
const artifactPath = path.join(root, "data/policy/policy-calculable-allow-11-approved.json");
const approvalPath = path.join(root, "data/policy/policy-calculable-allow-11-approval.json");
const scope = "CALCULABLE_FINANCIAL_CALCULATION";

function numberCanonical(value) {
  if (Object.is(value, -0)) return "-0.0";
  if (value === 0) return "0.0";
  const absolute = Math.abs(value);
  if (absolute >= 0.001 && absolute < 10_000_000) {
    const rendered = value.toString();
    return rendered.includes(".") ? rendered : `${rendered}.0`;
  }
  const [rawMantissa, rawExponent] = value.toExponential().replace("e", "E").split("E");
  const mantissa = rawMantissa.includes(".") ? rawMantissa : `${rawMantissa}.0`;
  return `${mantissa}E${Number(rawExponent)}`;
}

function canonical(value) {
  if (value === null) return "null";
  if (typeof value === "boolean") return String(value);
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("non-finite number");
    return Number.isInteger(value) && !Object.is(value, -0) ? String(value) : numberCanonical(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  return `{${Object.keys(value).sort().map((key) => `${canonical(key)}:${canonical(value[key])}`).join(",")}}`;
}

const digest = (value) => crypto.createHash("sha256").update(canonical(value)).digest("hex");
const read = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const write = (file, value) => fs.writeFileSync(file, `${canonical(value)}\n`, "utf8");

function argument(name) {
  const index = process.argv.indexOf(name);
  if (index < 0 || !process.argv[index + 1]) throw new Error(`missing ${name}`);
  return process.argv[index + 1];
}

const reviewer = argument("--reviewer");
const humanApprovedAt = argument("--approved-at");
const provenance = argument("--provenance");
if (Number.isNaN(Date.parse(humanApprovedAt))) throw new Error("invalid approval timestamp");

const base = read(basePath);
const prepared = read(candidatesPath);
const candidates = prepared.candidates;
if (prepared.status !== "READY_FOR_HUMAN_REVIEW_NOT_APPROVED" || candidates.length !== 11) {
  throw new Error("prepared candidate state is invalid");
}
if (new Set(candidates.map((item) => item.policyKey)).size !== 11) throw new Error("duplicate candidate key");

const baseByKey = new Map(base.policies.map((policy) => [policy.policyKey, policy]));
const approvedPolicyKeys = candidates.map((item) => item.policyKey).sort();
const approval = {
  schemaVersion: "odyssey-calculable-approval-v1",
  scope,
  reviewer,
  humanApprovedAt,
  provenance,
  sourceCandidateSchemaVersion: prepared.schemaVersion,
  sourceCandidateSha256: digest(prepared),
  sourceInformationalArtifactVersion: base.artifactVersion,
  sourceInformationalManifestSha256: base.manifestSha256,
  approvedDecision: "ALLOW",
  approvedPolicyCount: 11,
  approvedPolicyKeys,
  approvedModes: { ONE_TIME_FUNDING: 9, MONTHLY_EXPENSE_REDUCTION: 2 },
  approvalRelationship: {
    informationalAllow: 23,
    calculableApprovedSubset: 11,
    informationalOnlyRemainder: 12,
    excludeCalculable: 0,
    recheckCalculable: 0,
  },
  p0ExplicitSimplificationPolicyKeys: candidates
    .filter((item) => item.p0Simplifications.length > 0)
    .map((item) => item.policyKey).sort(),
  notApprovedByThisEvent: [
    "POLICY_BENEFIT_CONFIRMATION",
    "PLANNING_INTEGRATION",
    "RUNTIME_IMPORT_ACTIVATION",
  ],
};
const approvalHash = digest(approval);

const policies = candidates.map((candidate) => {
  const policy = baseByKey.get(candidate.policyKey);
  if (!policy) throw new Error(`candidate missing from informational artifact: ${candidate.policyKey}`);
  const version = policy.version;
  if (version.applicationStatus.decision !== "ALLOW" || version.reviewStatus !== "APPROVED"
      || version.sourceVersion !== candidate.sourceVersion
      || version.sourceLocator !== candidate.sourceLocatorCandidate
      || version.locatorSha256 !== candidate.sourceShaCandidate) {
    throw new Error(`candidate provenance mismatch: ${candidate.policyKey}`);
  }
  return {
    ...policy,
    version: {
      ...version,
      calculationMode: candidate.calculationMode,
      calculationRule: {
        adjustmentType: candidate.adjustmentType,
        amountUpperBound: candidate.amountUpperBound,
        maxMonths: candidate.maxMonths,
        sourceVersion: candidate.sourceVersion,
        approvedLocator: candidate.sourceLocatorCandidate,
        approvedSha256: candidate.sourceShaCandidate,
        goldenCase: {
          ...candidate.goldenCase,
          boundarySemantics: candidate.boundarySemantics,
          p0Simplifications: candidate.p0Simplifications,
        },
        humanApprovedAt,
        reviewer,
      },
    },
  };
});

const sourceKeys = new Set(policies.map((policy) => policy.version.sourceKey));
const artifact = {
  ...base,
  artifactVersion: `calculable-approved-11-${humanApprovedAt.replaceAll(":", "").replaceAll("-", "")}`,
  manifestSha256: "",
  sources: base.sources.filter((source) => sourceKeys.has(source.sourceKey)),
  policies,
  reviewGate: {
    status: "APPROVED",
    importable: true,
    reason: `${scope}; reviewer=${reviewer}; humanApprovedAt=${humanApprovedAt}; approvalProvenanceSha256=${approvalHash}`,
  },
};

const modeCounts = Object.groupBy
  ? Object.groupBy(policies, (policy) => policy.version.calculationMode)
  : policies.reduce((result, policy) => {
      (result[policy.version.calculationMode] ??= []).push(policy);
      return result;
    }, {});
if (artifact.sources.length !== 11 || modeCounts.ONE_TIME_FUNDING.length !== 9
    || modeCounts.MONTHLY_EXPENSE_REDUCTION.length !== 2) throw new Error("approved counts are invalid");

let simplificationCount = 0;
for (const policy of policies) {
  const rule = policy.version.calculationRule;
  const candidate = candidates.find((item) => item.policyKey === policy.policyKey);
  if (rule.adjustmentType !== policy.version.calculationMode
      || rule.sourceVersion !== policy.version.sourceVersion
      || rule.approvedLocator !== policy.version.sourceLocator
      || rule.approvedSha256 !== policy.version.locatorSha256
      || rule.amountUpperBound < 1 || !rule.goldenCase.input || !rule.goldenCase.expected
      || rule.humanApprovedAt !== humanApprovedAt || rule.reviewer !== reviewer
      || canonical(rule.goldenCase.input) !== canonical(candidate.goldenCase.input)
      || canonical(rule.goldenCase.expected) !== canonical(candidate.goldenCase.expected)) {
    throw new Error(`approved rule mismatch: ${policy.policyKey}`);
  }
  if (rule.adjustmentType === "ONE_TIME_FUNDING" && rule.maxMonths !== null) throw new Error("invalid one-time months");
  if (rule.adjustmentType === "MONTHLY_EXPENSE_REDUCTION" && !(rule.maxMonths >= 1 && rule.maxMonths <= 120)) {
    throw new Error("invalid monthly months");
  }
  if (rule.goldenCase.p0Simplifications.length > 0) simplificationCount += 1;
}
if (simplificationCount !== 2) throw new Error("expected two P0 simplifications");

artifact.manifestSha256 = digest(artifact);
const expectedManifest = artifact.manifestSha256;
artifact.manifestSha256 = "";
if (digest(artifact) !== expectedManifest) throw new Error("manifest is not reproducible");
artifact.manifestSha256 = expectedManifest;

write(approvalPath, approval);
write(artifactPath, artifact);
console.log(JSON.stringify({
  policies: policies.length,
  sources: artifact.sources.length,
  oneTime: modeCounts.ONE_TIME_FUNDING.length,
  monthly: modeCounts.MONTHLY_EXPENSE_REDUCTION.length,
  approvalHash,
  manifestSha256: artifact.manifestSha256,
}));
