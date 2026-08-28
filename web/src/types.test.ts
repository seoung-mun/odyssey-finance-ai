import { parsePlanOption, parseReplanEvents, parseSample } from "./types";

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

it("accepts signed safe integers in percentile bands", () => {
  expect(() =>
    parsePlanOption({
      ...validOption,
      percentileBands: [
        {
          monthIndex: 1,
          p10: Number.MIN_SAFE_INTEGER,
          p25: -40,
          p50: -30,
          p75: -20,
          p90: -10,
        },
      ],
    }),
  ).not.toThrow();
});

it.each([1.5, Number.MAX_SAFE_INTEGER + 1])(
  "rejects non-safe percentile value %s",
  (p50) => {
    expect(() =>
      parsePlanOption({
        ...validOption,
        percentileBands: [{ monthIndex: 1, p10: 10, p25: 20, p50, p75: p50, p90: p50 }],
      }),
    ).toThrow("INVALID_RESPONSE");
  },
);

it("still rejects negative non-band money", () => {
  expect(() => parsePlanOption({ ...validOption, recommendedMonthlySpending: -1 })).toThrow(
    "INVALID_RESPONSE",
  );
});

it("parses replan history and rejects unsafe identifiers", () => {
  const event = {
    id: 12,
    triggerType: "USER_REQUESTED",
    userDecision: null,
    createdAt: "2026-08-26T12:00:00+09:00",
    proposedPlanVersion: { id: 9 },
  };
  expect(parseReplanEvents([event])).toEqual([
    expect.objectContaining({ id: 12, triggerType: "USER_REQUESTED", proposedPlanVersionId: 9 }),
  ]);
  expect(() => parseReplanEvents([{ ...event, id: Number.MAX_SAFE_INTEGER + 1 }])).toThrow(
    "INVALID_RESPONSE",
  );
});
