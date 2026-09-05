#!/usr/bin/env node
"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "../..");
const informationalPath = path.join(root, "data/policy/policy-artifact-informational-approved-23.json");
const calculablePath = path.join(root, "data/policy/policy-calculable-allow-11-approved.json");
const outputPath = path.join(root, "data/policy/policy-artifact-calculable-approved-23.json");

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

const read = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const digest = (value) => crypto.createHash("sha256").update(canonical(value)).digest("hex");
const informational = read(informationalPath);
const calculable = read(calculablePath);
const calculableByKey = new Map(calculable.policies.map((policy) => [policy.policyKey, policy]));

if (informational.policies.length !== 23 || calculable.policies.length !== 11) {
  throw new Error("expected 23 informational and 11 calculable policies");
}
for (const policy of calculable.policies) {
  const base = informational.policies.find((item) => item.policyKey === policy.policyKey);
  if (!base || base.version.sourceVersion !== policy.version.sourceVersion
      || base.version.sourceLocator !== policy.version.sourceLocator
      || base.version.locatorSha256 !== policy.version.locatorSha256) {
    throw new Error(`calculable policy does not match informational catalog: ${policy.policyKey}`);
  }
}

const artifact = {
  ...informational,
  artifactVersion: calculable.artifactVersion.replace("calculable-approved-11", "calculable-runtime-approved-23"),
  manifestSha256: "",
  policies: informational.policies.map((policy) => calculableByKey.get(policy.policyKey) ?? policy),
  reviewGate: calculable.reviewGate,
};

const calculablePolicies = artifact.policies.filter((policy) => policy.version.calculationRule !== null);
const informationalOnly = artifact.policies.filter((policy) => policy.version.calculationRule === null);
const oneTime = calculablePolicies.filter((policy) => policy.version.calculationMode === "ONE_TIME_FUNDING");
const monthly = calculablePolicies.filter((policy) => policy.version.calculationMode === "MONTHLY_EXPENSE_REDUCTION");
if (artifact.sources.length !== 23 || calculablePolicies.length !== 11 || informationalOnly.length !== 12
    || oneTime.length !== 9 || monthly.length !== 2
    || artifact.policies.some((policy) => policy.version.applicationStatus.decision !== "ALLOW")) {
  throw new Error("runtime activation artifact counts are invalid");
}

artifact.manifestSha256 = digest(artifact);
const expectedManifest = artifact.manifestSha256;
artifact.manifestSha256 = "";
if (digest(artifact) !== expectedManifest) throw new Error("manifest is not reproducible");
artifact.manifestSha256 = expectedManifest;
fs.writeFileSync(outputPath, `${canonical(artifact)}\n`, "utf8");
console.log(JSON.stringify({
  output: path.relative(root, outputPath),
  policies: artifact.policies.length,
  calculable: calculablePolicies.length,
  informationalOnly: informationalOnly.length,
  oneTime: oneTime.length,
  monthly: monthly.length,
  manifestSha256: artifact.manifestSha256,
}));
