import { parsePlanOption, parseSample } from "./types";

const validOption = {
  id: 7,
  optionType: "PRESET",
  nominalLevel: 0.8,
  recommendedMonthlySpending: 920_000,
  requiredReductionRate: 0.3,
  simulationCoverage: 0.8,
  historicalFeasibilityRatio: 0.5,
  aggressiveWarning: false,
  effectiveMaxReductionRate: 0.4,
  floorApplied: false,
  targetCoverageMet: true,
  percentileBands: [{ monthIndex: 1, p10: 10, p25: 20, p50: 30, p75: 40, p90: 50 }],
};

it("requires sampleDataLoadedAt in a sample response", () => {
  expect(() => parseSample({ loaded: true })).toThrow("INVALID_RESPONSE");
});

it("rejects an impossible sampleDataLoadedAt calendar date", () => {
  expect(() => parseSample({ loaded: true, sampleDataLoadedAt: "2026-02-31T00:00:00Z" })).toThrow(
    "INVALID_RESPONSE",
  );
});

it("rejects effectiveMaxReductionRate outside 0..1", () => {
  expect(() => parsePlanOption({ ...validOption, effectiveMaxReductionRate: 1.01 })).toThrow(
    "INVALID_RESPONSE",
  );
});

it.each([-0.1, 1.1])("rejects nominalLevel %s outside 0..1", (nominalLevel) => {
  expect(() => parsePlanOption({ ...validOption, nominalLevel })).toThrow("INVALID_RESPONSE");
});

it.each([null, 0, 1])("accepts nominalLevel boundary %s", (nominalLevel) => {
  expect(() => parsePlanOption({ ...validOption, nominalLevel })).not.toThrow();
});

it("rejects percentile bands whose quantiles are out of order", () => {
  expect(() =>
    parsePlanOption({
      ...validOption,
      percentileBands: [{ monthIndex: 1, p10: 10, p25: 40, p50: 30, p75: 45, p90: 50 }],
    }),
  ).toThrow("INVALID_RESPONSE");
});

it.each([0, 1.5])("rejects percentile band monthIndex %s", (monthIndex) => {
  expect(() =>
    parsePlanOption({
      ...validOption,
      percentileBands: [{ ...validOption.percentileBands[0], monthIndex }],
    }),
  ).toThrow("INVALID_RESPONSE");
});

it("accepts percentile band monthIndex 1", () => {
  expect(() => parsePlanOption(validOption)).not.toThrow();
});
