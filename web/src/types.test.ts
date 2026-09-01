import {
  parseDemoSeedResponse,
  parseDemoTesters,
  parsePlanOption,
  parsePolicyScenario,
  parsePolicySearchResponse,
  parseReplanEvents,
  parseTransactionPage,
} from "./types";

const validOption = {
  id: 7,
  optionType: "PRESET",
  nominalLevel: 0.8,
  recommendedMonthlySpending: 920_000,
  requiredReductionRate: 0.3,
  simulationCoverage: 0.8,
  historicalFeasibilityRatio: 0.5,
  aggressiveWarning: false,
  targetCoverageMet: true,
  percentileBands: [{ monthIndex: 1, p10: 10, p25: 20, p50: 30, p75: 40, p90: 50 }],
};

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
  expect(() => parseReplanEvents([{ ...event, triggerType: "DELETE_ALL" }])).toThrow(
    "INVALID_RESPONSE",
  );
  expect(() => parseReplanEvents([{ ...event, userDecision: "ACCEPTED_UNKNOWN" }])).toThrow(
    "INVALID_RESPONSE",
  );
  expect(() => parseReplanEvents([{ ...event, unexpected: true }])).toThrow("INVALID_RESPONSE");
});

it("parses the complete demo tester and seed contracts", () => {
  const tester = {
    testerId: "youth-steady",
    displayName: "꾸준한 사회초년생",
    description: "생활비를 줄여 비상금을 준비합니다.",
    ageGroup: "YOUTH",
    monthlyIncome: 3_200_000,
    monthlyFixedCost: 1_100_000,
    goalName: "비상금",
    goalTargetAmount: 12_000_000,
    goalMonths: 12,
  };

  expect(parseDemoTesters([tester])).toEqual([tester]);
  expect(
    parseDemoSeedResponse({
      testerId: "youth-steady",
      scenarioVersion: 1,
      seededAt: "2026-08-28T10:00:00+09:00",
    }),
  ).toEqual({
    testerId: "youth-steady",
    scenarioVersion: 1,
    seededAt: "2026-08-28T10:00:00+09:00",
  });
});

it("rejects invalid demo contract values", () => {
  const tester = {
    testerId: "youth-steady",
    displayName: "꾸준한 사회초년생",
    description: "생활비를 줄여 비상금을 준비합니다.",
    ageGroup: "YOUTH",
    monthlyIncome: 3_200_000,
    monthlyFixedCost: 1_100_000,
    goalName: "비상금",
    goalTargetAmount: 12_000_000,
    goalMonths: 12,
  };

  expect(() => parseDemoTesters([{ ...tester, goalMonths: 0 }])).toThrow("INVALID_RESPONSE");
  expect(() =>
    parseDemoSeedResponse({
      testerId: "youth-steady",
      scenarioVersion: 0,
      seededAt: "2026-08-28T10:00:00+09:00",
    }),
  ).toThrow("INVALID_RESPONSE");
});

it("parses policy questions and rejects a fourth answer option flow response", () => {
  expect(
    parsePolicySearchResponse({
      type: "QUESTION",
      question: {
        questionId: "region",
        label: "희망 지역을 선택해 주세요",
        options: [{ value: "SEOUL", label: "서울" }],
      },
    }),
  ).toEqual(expect.objectContaining({ type: "QUESTION" }));
  expect(() =>
    parsePolicySearchResponse({ type: "RESULTS", results: [{ policyVersionId: 1 }] }),
  ).toThrow("INVALID_RESPONSE");
});

it("parses policy scenario numbers without deriving a browser-side delta", () => {
  const summary = {
    recommendedMonthlySpending: 900_000,
    requiredReductionRate: 0.2,
    simulationCoverage: 0.8,
  };
  const source = {
    organization: "복지기관",
    officialUrl: "https://example.go.kr/policy",
    sourceVersion: "2026-09",
    lastVerifiedAt: "2026-09-01T10:00:00+09:00",
    locators: ["공고문 3쪽"],
  };
  expect(
    parsePolicyScenario({
      currentPlanSummary: summary,
      assumedPlanSummary: { ...summary, recommendedMonthlySpending: 950_000 },
      adjustment: {
        type: "ONE_TIME_FUNDING",
        amountWon: 1_000_000,
        startYearMonth: "2026-10",
        sourceVersion: "2026-09",
      },
      assumptionNotice: "현재 계획에는 반영되지 않습니다.",
      source,
    }).assumedPlanSummary.recommendedMonthlySpending,
  ).toBe(950_000);
});

const policySource = {
  organization: "복지기관",
  officialUrl: "https://example.go.kr/policy",
  sourceVersion: "2026-09",
  lastVerifiedAt: "2026-09-01T10:00:00+09:00",
  locators: ["공고문 3쪽"],
};
const policyResult = {
  policyVersionId: 1,
  title: "청년 주거 지원",
  summary: "공식 정책",
  planConnection: "주거비와 연결",
  supportDetails: "기관 확인",
  confirmedConditions: [],
  additionalChecks: [],
  applicationPeriod: "상시",
  asOfDate: "2026-09-01",
  eligibilityStatus: "NEEDS_CONFIRMATION",
  calculationMode: "ONE_TIME_FUNDING",
  source: policySource,
};
const policyScenario = {
  currentPlanSummary: {
    recommendedMonthlySpending: 900_000,
    requiredReductionRate: 0.2,
    simulationCoverage: 0.8,
  },
  assumedPlanSummary: {
    recommendedMonthlySpending: 950_000,
    requiredReductionRate: 0.15,
    simulationCoverage: 0.85,
  },
  adjustment: {
    type: "ONE_TIME_FUNDING",
    amountWon: 1_000_000,
    startYearMonth: "2026-10",
    sourceVersion: "2026-09",
  },
  assumptionNotice: "현재 계획에는 반영되지 않습니다.",
  source: policySource,
};

it.each([
  ["invalid official URI", { ...policyResult, source: { ...policySource, officialUrl: "not a uri" } }],
  ["empty source locators", { ...policyResult, source: { ...policySource, locators: [] } }],
  ["zero policy version", { ...policyResult, policyVersionId: 0 }],
  ["impossible calendar date", { ...policyResult, asOfDate: "2026-02-31" }],
])("rejects policy result with %s", (_name, invalid) => {
  expect(() => parsePolicySearchResponse({ type: "RESULTS", results: [invalid] })).toThrow(
    "INVALID_RESPONSE",
  );
});

it("rejects more than three policy results", () => {
  expect(() =>
    parsePolicySearchResponse({
      type: "RESULTS",
      results: [1, 2, 3, 4].map((policyVersionId) => ({ ...policyResult, policyVersionId })),
    }),
  ).toThrow("INVALID_RESPONSE");
});

it.each([
  ["zero amount", { ...policyScenario.adjustment, amountWon: 0 }],
  ["invalid start month", { ...policyScenario.adjustment, startYearMonth: "2026-13" }],
  ["unknown adjustment field", { ...policyScenario.adjustment, unexpected: true }],
])("rejects policy scenario with %s", (_name, adjustment) => {
  expect(() => parsePolicyScenario({ ...policyScenario, adjustment })).toThrow("INVALID_RESPONSE");
});

it("rejects policy reduction rates above one", () => {
  expect(() =>
    parsePolicyScenario({
      ...policyScenario,
      currentPlanSummary: { ...policyScenario.currentPlanSummary, requiredReductionRate: 1.01 },
    }),
  ).toThrow("INVALID_RESPONSE");
});

it("rejects a null transaction sourceId", () => {
  expect(() =>
    parseTransactionPage({
      items: [
        {
          id: 1,
          transactionAt: "2026-09-01T10:00:00+09:00",
          amount: 1,
          transactionType: "PAYMENT",
          category: "식비",
          merchantName: null,
          sourceId: null,
          externalTransactionId: "tx-1",
          refundStatus: "NOT_APPLICABLE",
        },
      ],
      nextCursor: null,
    }),
  ).toThrow("INVALID_RESPONSE");
});
