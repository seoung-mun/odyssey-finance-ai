#!/usr/bin/env node

const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "../..");
const decisionsPath = path.join(root, "data/policy/odyssey_policy_51_calculable_decisions_v3.json");
const handoffPath = path.join(root, "data/policy/odyssey_policy_51_runtime_metadata_handoff_v1.json");
const outputPath = path.join(root, "data/policy/policy-calculable-allow-11-approval-candidates.json");
const simplifiedKeys = new Set([
  "YOUTH_CENTER_20260406005400212460",
  "YOUTH_CENTER_20260806005400213321",
]);

const read = (file) => JSON.parse(fs.readFileSync(file, "utf8"));

function sorted(value) {
  if (Array.isArray(value)) return value.map(sorted);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sorted(value[key])]));
  }
  return value;
}

function goldenCase(mode, amountUpperBound) {
  if (mode === "ONE_TIME_FUNDING") {
    return {
      input: {
        institutionConfirmed: true,
        amountWon: amountUpperBound,
        startYearMonth: "2026-10",
      },
      expected: {
        adjustmentType: mode,
        amount: amountUpperBound,
        applicationYearMonth: "2026-10",
      },
    };
  }
  return {
    input: {
      institutionConfirmed: true,
      amountWon: amountUpperBound,
      startYearMonth: "2026-10",
      endYearMonth: "2027-09",
    },
    expected: {
      adjustmentType: mode,
      monthlyAmount: amountUpperBound,
      monthCount: 12,
      totalNominalBenefit: amountUpperBound * 12,
    },
  };
}

function prepare() {
  const decisions = read(decisionsPath);
  const handoff = read(handoffPath);
  const handoffByKey = new Map(handoff.policies.map((policy) => [policy.policyKey, policy]));
  const allow = decisions.selected.filter((item) => item.top3DecisionAsOf20260901 === "ALLOW");
  if (decisions.selected.length !== 16 || allow.length !== 11) {
    throw new Error("expected 16 calculable candidates and 11 ALLOW candidates");
  }

  const candidates = allow.map((item) => {
    const policy = handoffByKey.get(item.policyKey);
    if (!policy || policy.title !== item.title || policy.supportGoal !== item.supportGoal) {
      throw new Error(`handoff mapping mismatch: ${item.policyKey}`);
    }
    const rule = item.proposedCalculationRule;
    if (
      policy.version.applicationStatus.decision !== "ALLOW" ||
      policy.version.sourceVersion !== rule.sourceVersion ||
      policy.version.sourceLocator !== rule.approvedLocatorCandidate ||
      policy.version.locatorSha256 !== rule.approvedSha256Candidate
    ) {
      throw new Error(`source/provenance mismatch: ${item.policyKey}`);
    }
    const mode = item.proposedCalculationMode;
    if (!new Set(["ONE_TIME_FUNDING", "MONTHLY_EXPENSE_REDUCTION"]).has(mode)) {
      throw new Error(`unsupported calculation mode: ${item.policyKey}`);
    }
    const isMonthly = mode === "MONTHLY_EXPENSE_REDUCTION";
    if ((isMonthly && rule.maxMonths !== 12) || (!isMonthly && rule.maxMonths !== null)) {
      throw new Error(`unexpected duration semantics: ${item.policyKey}`);
    }
    const golden = goldenCase(mode, rule.amountUpperBound);
    return {
      policyKey: item.policyKey,
      title: item.title,
      supportGoal: item.supportGoal,
      applicationDecision: "ALLOW",
      calculationMode: mode,
      adjustmentType: mode,
      amountUpperBound: rule.amountUpperBound,
      maxMonths: rule.maxMonths,
      calculationRule: {
        amountSemantics: "INSTITUTION_CONFIRMED_ACTUAL_AMOUNT",
        amountConstraint: "0 < amountWon <= amountUpperBound",
        applicationSemantics: isMonthly
          ? "Apply amountWon once per inclusive month from startYearMonth through endYearMonth"
          : "Apply amountWon once in startYearMonth",
      },
      sourceVersion: rule.sourceVersion,
      sourceLocatorCandidate: rule.approvedLocatorCandidate,
      sourceShaCandidate: rule.approvedSha256Candidate,
      goldenCase: golden,
      boundarySemantics: {
        rejectAmountWonLessThanOrEqualTo: 0,
        rejectAmountWonGreaterThan: rule.amountUpperBound,
        startYearMonthRequired: true,
        endYearMonth: isMonthly ? "REQUIRED_AND_NOT_BEFORE_START" : "NOT_USED",
        inclusiveMonthCount: isMonthly,
        rejectMonthCountGreaterThan: rule.maxMonths,
      },
      p0Simplifications: simplifiedKeys.has(item.policyKey)
        ? [
            {
              code: "P0_EXPLICIT_SIMPLIFICATION",
              description: "본인부담 1,000원을 별도 cashflow 또는 amount 차감으로 반영하지 않고 amountUpperBound=300000을 유지한다.",
              maximumDifferenceWon: 1000,
            },
          ]
        : [],
      auditVerdict: "READY_FOR_HUMAN_APPROVAL",
    };
  });

  const oneTime = candidates.filter((item) => item.calculationMode === "ONE_TIME_FUNDING");
  const monthly = candidates.filter((item) => item.calculationMode === "MONTHLY_EXPENSE_REDUCTION");
  if (oneTime.length !== 9 || monthly.length !== 2 || candidates.some((item) => item.auditVerdict !== "READY_FOR_HUMAN_APPROVAL")) {
    throw new Error("candidate readiness counts are invalid");
  }
  for (const item of candidates) {
    const input = item.goldenCase.input;
    const expected = item.goldenCase.expected;
    if (input.amountWon <= 0 || input.amountWon > item.amountUpperBound) {
      throw new Error(`golden amount is outside cap: ${item.policyKey}`);
    }
    if (item.calculationMode === "ONE_TIME_FUNDING") {
      if (expected.amount !== input.amountWon || expected.applicationYearMonth !== input.startYearMonth) {
        throw new Error(`invalid one-time golden case: ${item.policyKey}`);
      }
    } else if (
      expected.monthCount > item.maxMonths ||
      expected.monthlyAmount !== input.amountWon ||
      expected.totalNominalBenefit !== input.amountWon * expected.monthCount
    ) {
      throw new Error(`invalid monthly golden case: ${item.policyKey}`);
    }
  }

  return {
    schemaVersion: "odyssey-calculable-approval-candidates-v1",
    status: "READY_FOR_HUMAN_REVIEW_NOT_APPROVED",
    preparedAsOf: "2026-09-04",
    sourceDecisionArtifact: decisions.schemaVersion,
    sourceRuntimeArtifactVersion: handoff.artifactVersion,
    candidateCount: candidates.length,
    oneTimeCount: oneTime.length,
    monthlyCount: monthly.length,
    calculableApproval: "NOT_GRANTED",
    runtimeActivation: false,
    candidates,
  };
}

const output = prepare();
fs.writeFileSync(outputPath, `${JSON.stringify(sorted(output))}\n`, "utf8");
console.log(
  JSON.stringify({
    output: path.relative(root, outputPath),
    candidates: output.candidateCount,
    oneTime: output.oneTimeCount,
    monthly: output.monthlyCount,
    status: output.status,
  }),
);
