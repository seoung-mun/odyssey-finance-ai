export type Explanation = {
  status: "PENDING" | "PROCESSING" | "READY" | "FALLBACK" | "FAILED";
  text: string | null;
};
export type PercentileBand = {
  monthIndex: number;
  p10: number;
  p25: number;
  p50: number;
  p75: number;
  p90: number;
};
export type PlanOption = {
  id: number;
  optionType: "PRESET" | "CUSTOM";
  nominalLevel: number | null;
  recommendedMonthlySpending: number;
  requiredReductionRate: number;
  simulationCoverage: number;
  historicalFeasibilityRatio: number;
  aggressiveWarning: boolean;
  targetCoverageMet: boolean;
  percentileBands: PercentileBand[];
};
export type PlanSnapshot = {
  monthlyIncome: number;
  monthlyFixedCost: number;
  targetAmount: number;
  currentSaved: number;
  targetDate: string;
  availableVariableBudget: number;
  currentAvgVariableSpending: number;
  remainingMonths: number;
};
export type PlanVersion = {
  id: number;
  status: "PROPOSED" | "ACTIVE" | "SUPERSEDED" | "REJECTED" | "INFEASIBLE" | "STALE";
  infeasibleReason: string | null;
  explanation: Explanation;
  options: PlanOption[];
  snapshot?: PlanSnapshot | null;
};
export type Dashboard = {
  goal: null | {
    id: number;
    name: string;
    targetAmount: number;
    currentSavedAmount: number;
    targetDate: string;
    remainingMonths: number;
  };
  activePlan: (PlanVersion & { asOfDate?: string }) | null;
  selectedOption: PlanOption | null;
  pendingProposal: null | {
    planVersionId: number;
    replanEventId: number | null;
    triggerType: string | null;
  };
  monthProgress: null | {
    plannedMonthlySpending: number;
    actualToDate: number;
    paceRatio: number;
    daysElapsed: number;
  };
};
export type AuthTokens = { accessToken: string; expiresIn: number; isNewUser: boolean };
export type Me = { activeGoalId: number | null };
export type DemoTester = {
  testerId: string;
  displayName: string;
  description: string;
  ageGroup: "YOUTH" | "MIDDLE_AGED" | "SENIOR";
  monthlyIncome: number;
  monthlyFixedCost: number;
  goalName: string;
  goalTargetAmount: number;
  goalMonths: number;
};
export type DemoSeedResponse = {
  testerId: string;
  scenarioVersion: number;
  seededAt: string;
};
export type ReplanEvent = {
  id: number;
  triggerType: string;
  userDecision: string | null;
  createdAt: string;
  proposedPlanVersionId: number | null;
};
export type UserProfile = { birthDate: string | null; regionCode: string | null; updatedAt: string };
export type FinancialProfile = {
  monthlyIncome: number;
  monthlyFixedCost: number;
  updatedAt: string;
};
export type Goal = {
  id: number;
  name: string;
  targetAmount: number;
  currentSavedAmount: number;
  targetDate: string;
  status: "ACTIVE" | "ACHIEVED" | "CANCELLED";
  remainingMonths: number;
};
export type PlanVersionSummary = {
  id: number;
  versionNo: number;
  generationType: "INITIAL" | "MONTHLY_REGULAR" | "TRIGGERED_REPLAN" | "USER_REQUESTED";
  status: PlanVersion["status"];
  asOfDate: string;
  infeasibleReason: string | null;
  createdAt: string;
  activatedAt: string | null;
};
export type ScheduledExpense = {
  id: number;
  name: string;
  amount: number;
  scheduledDate: string;
  status: "PLANNED" | "COMPLETED" | "CANCELLED";
  matchedTransactionCount: number;
  matchedAmount: number;
  triggeredReplanEventId: number | null;
};
export type DemoTransactionsResponse = {
  testerId: "youth" | "middle" | "senior";
  scenarioVersion: number;
  inserted: number;
  completeMonths: number;
};
export type Transaction = {
  id: number;
  transactionAt: string;
  amount: number;
  transactionType: "PAYMENT" | "REFUND";
  category: string;
  merchantName: string | null;
  sourceId: string;
  externalTransactionId: string | null;
  refundStatus: "NOT_APPLICABLE" | "UNMATCHED" | "PENDING" | "PARTIALLY_LINKED" | "LINKED";
};
export type TransactionPage = { items: Transaction[]; nextCursor: string | null };
export type MonthlySpending = {
  yearMonth: string;
  totalVariableSpending: number;
  grossPaymentSpending: number;
  linkedRefundAmount: number;
  unmatchedRefundInflow: number;
  adjustedConsumption: number;
  netCashFlow: number;
  bootstrapEligibleSpending: number;
};
export type CategorySpending = {
  months: number;
  currentAvgVariableSpending: number;
  categories: { category: string; monthlyAverage: number }[];
};
export type PolicySupportGoal =
  | "PURCHASE"
  | "JEONSE"
  | "MONTHLY_RENT"
  | "PUBLIC_RENTAL"
  | "SUBSCRIPTION"
  | "MOVING_COST"
  | "GUARANTEE"
  | "DORMITORY";
export type PolicyAnswer = { questionId: string; value: string };
export type PolicySource = {
  organization: string;
  officialUrl: string;
  sourceVersion: string;
  lastVerifiedAt: string;
  locators: string[];
};
export type PolicyResult = {
  policyVersionId: number;
  title: string;
  summary: string;
  planConnection: string;
  supportDetails: string;
  confirmedConditions: string[];
  additionalChecks: string[];
  applicationPeriod: string;
  asOfDate: string;
  eligibilityStatus: "NEEDS_CONFIRMATION";
  calculationMode:
    | "INFORMATIONAL"
    | "ELIGIBILITY_ONLY"
    | "ONE_TIME_FUNDING"
    | "MONTHLY_EXPENSE_REDUCTION";
  source: PolicySource;
};
export type PolicySearchResponse =
  | {
      type: "QUESTION";
      question: { questionId: string; label: string; options: { value: string; label: string }[] };
    }
  | { type: "RESULTS"; results: PolicyResult[] };
export type PolicyPlanSummary = {
  recommendedMonthlySpending: number;
  requiredReductionRate: number;
  simulationCoverage: number;
};
export type PolicyScenario = {
  currentPlanSummary: PolicyPlanSummary;
  assumedPlanSummary: PolicyPlanSummary;
  adjustment: {
    type: "ONE_TIME_FUNDING" | "MONTHLY_EXPENSE_REDUCTION";
    amountWon: number;
    startYearMonth: string;
    endYearMonth?: string;
    sourceVersion: string;
  };
  assumptionNotice: string;
  source: PolicySource;
};

type RecordValue = Record<string, unknown>;

const record = (value: unknown): RecordValue => {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    throw new Error("INVALID_RESPONSE");
  return Object.fromEntries(Object.entries(value));
};
const exactKeys = (value: RecordValue, allowed: readonly string[]) => {
  if (Object.keys(value).some((key) => !allowed.includes(key)))
    throw new Error("INVALID_RESPONSE");
};
const string = (value: unknown): string => {
  if (typeof value !== "string") throw new Error("INVALID_RESPONSE");
  return value;
};
const nullableString = (value: unknown): string | null => {
  return value === null ? null : string(value);
};
const number = (value: unknown): number => {
  if (typeof value !== "number" || !Number.isFinite(value)) throw new Error("INVALID_RESPONSE");
  return value;
};
const integer = (value: unknown): number => {
  const parsed = number(value);
  if (!Number.isSafeInteger(parsed)) throw new Error("INVALID_RESPONSE");
  return parsed;
};
const money = (value: unknown): number => {
  const parsed = integer(value);
  if (parsed < 0) throw new Error("INVALID_RESPONSE");
  return parsed;
};
const positiveMoney = (value: unknown): number => {
  const parsed = money(value);
  if (parsed < 1) throw new Error("INVALID_RESPONSE");
  return parsed;
};
const boolean = (value: unknown): boolean => {
  if (typeof value !== "boolean") throw new Error("INVALID_RESPONSE");
  return value;
};
const nullableInteger = (value: unknown): number | null => {
  return value === null ? null : integer(value);
};
const dateTime = (value: unknown): string => {
  const parsed = string(value);
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-](\d{2}):(\d{2}))$/.exec(
      parsed,
    );
  if (!match) throw new Error("INVALID_RESPONSE");
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const offsetHour = Number(match[7] ?? 0);
  const offsetMinute = Number(match[8] ?? 0);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth =
    [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] ?? 0;
  if (
    day < 1 ||
    day > daysInMonth ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    offsetHour > 23 ||
    offsetMinute > 59
  )
    throw new Error("INVALID_RESPONSE");
  return parsed;
};
const enumValue = <const T extends readonly string[]>(value: unknown, allowed: T): T[number] => {
  if (typeof value !== "string" || !allowed.includes(value)) throw new Error("INVALID_RESPONSE");
  return value;
};
const array = <T>(value: unknown, parse: (item: unknown) => T): T[] => {
  if (!Array.isArray(value)) throw new Error("INVALID_RESPONSE");
  return value.map(parse);
};

const parseBand = (value: unknown): PercentileBand => {
  const item = record(value);
  const band = {
    monthIndex: integer(item.monthIndex),
    p10: integer(item.p10),
    p25: integer(item.p25),
    p50: integer(item.p50),
    p75: integer(item.p75),
    p90: integer(item.p90),
  };
  if (
    band.monthIndex < 1 ||
    !(band.p10 <= band.p25 && band.p25 <= band.p50 && band.p50 <= band.p75 && band.p75 <= band.p90)
  )
    throw new Error("INVALID_RESPONSE");
  return band;
};
export const parseExplanation = (value: unknown): Explanation => {
  const item = record(value);
  return {
    status: enumValue(item.status, ["PENDING", "PROCESSING", "READY", "FALLBACK", "FAILED"]),
    text: nullableString(item.text),
  };
};
export const parsePlanOption = (value: unknown): PlanOption => {
  const item = record(value);
  const coverage = number(item.simulationCoverage);
  const feasibility = number(item.historicalFeasibilityRatio);
  const nominalLevel = item.nominalLevel === null ? null : number(item.nominalLevel);
  if (
    coverage < 0 ||
    coverage > 1 ||
    feasibility < 0 ||
    feasibility > 1 ||
    (nominalLevel !== null && (nominalLevel < 0 || nominalLevel > 1))
  )
    throw new Error("INVALID_RESPONSE");
  return {
    id: integer(item.id),
    optionType: enumValue(item.optionType, ["PRESET", "CUSTOM"]),
    nominalLevel,
    recommendedMonthlySpending: money(item.recommendedMonthlySpending),
    requiredReductionRate: number(item.requiredReductionRate),
    simulationCoverage: coverage,
    historicalFeasibilityRatio: feasibility,
    aggressiveWarning: boolean(item.aggressiveWarning),
    targetCoverageMet: boolean(item.targetCoverageMet),
    percentileBands: array(item.percentileBands, parseBand),
  };
};
export const parsePlanVersion = (value: unknown): PlanVersion => {
  const item = record(value);
  const snapshot = item.snapshot == null ? null : record(item.snapshot);
  return {
    id: integer(item.id),
    status: enumValue(item.status, [
      "PROPOSED",
      "ACTIVE",
      "SUPERSEDED",
      "REJECTED",
      "INFEASIBLE",
      "STALE",
    ]),
    infeasibleReason: nullableString(item.infeasibleReason),
    explanation: parseExplanation(item.explanation),
    options: array(item.options, parsePlanOption),
    snapshot:
      snapshot === null
        ? null
        : {
            monthlyIncome: money(snapshot.monthlyIncome),
            monthlyFixedCost: money(snapshot.monthlyFixedCost),
            targetAmount: money(snapshot.targetAmount),
            currentSaved: money(snapshot.currentSaved),
            targetDate: date(snapshot.targetDate),
            availableVariableBudget: signedMoney(snapshot.availableVariableBudget),
            currentAvgVariableSpending: signedMoney(snapshot.currentAvgVariableSpending),
            remainingMonths: integer(snapshot.remainingMonths),
          },
  };
};
export const parseDashboard = (value: unknown): Dashboard => {
  const item = record(value);
  const goal = item.goal === null ? null : record(item.goal);
  const proposal = item.pendingProposal === null ? null : record(item.pendingProposal);
  const progress = item.monthProgress === null ? null : record(item.monthProgress);
  return {
    goal:
      goal === null
        ? null
        : {
            id: integer(goal.id),
            name: string(goal.name),
            targetAmount: money(goal.targetAmount),
            currentSavedAmount: money(goal.currentSavedAmount),
            targetDate: string(goal.targetDate),
            remainingMonths: integer(goal.remainingMonths),
          },
    activePlan:
      item.activePlan === null
        ? null
        : {
            ...parsePlanVersion(item.activePlan),
            asOfDate: date(record(item.activePlan).asOfDate),
          },
    selectedOption: item.selectedOption === null ? null : parsePlanOption(item.selectedOption),
    pendingProposal:
      proposal === null
        ? null
        : {
            planVersionId: integer(proposal.planVersionId),
            replanEventId: nullableInteger(proposal.replanEventId),
            triggerType: nullableString(proposal.triggerType),
          },
    monthProgress:
      progress === null
        ? null
        : {
            plannedMonthlySpending: money(progress.plannedMonthlySpending),
            actualToDate: money(progress.actualToDate),
            paceRatio: number(progress.paceRatio),
            daysElapsed: integer(progress.daysElapsed),
          },
  };
};
export const parseAuthTokens = (value: unknown): AuthTokens => {
  const item = record(value);
  return {
    accessToken: string(item.accessToken),
    expiresIn: integer(item.expiresIn),
    isNewUser: boolean(item.isNewUser),
  };
};
export const parseMe = (value: unknown): Me => {
  return { activeGoalId: nullableInteger(record(value).activeGoalId) };
};
export const parseDemoTesters = (value: unknown): DemoTester[] =>
  array(value, (entry) => {
    const item = record(entry);
    const goalTargetAmount = money(item.goalTargetAmount);
    const goalMonths = integer(item.goalMonths);
    if (goalTargetAmount < 1 || goalMonths < 1) throw new Error("INVALID_RESPONSE");
    return {
      testerId: string(item.testerId),
      displayName: string(item.displayName),
      description: string(item.description),
      ageGroup: enumValue(item.ageGroup, ["YOUTH", "MIDDLE_AGED", "SENIOR"]),
      monthlyIncome: money(item.monthlyIncome),
      monthlyFixedCost: money(item.monthlyFixedCost),
      goalName: string(item.goalName),
      goalTargetAmount,
      goalMonths,
    };
  });
export const parseDemoSeedResponse = (value: unknown): DemoSeedResponse => {
  const item = record(value);
  const scenarioVersion = integer(item.scenarioVersion);
  if (scenarioVersion < 1) throw new Error("INVALID_RESPONSE");
  return {
    testerId: string(item.testerId),
    scenarioVersion,
    seededAt: dateTime(item.seededAt),
  };
};
export const parseId = (value: unknown): { id: number } => {
  return { id: integer(record(value).id) };
};
export const parsePlanSummaries = (value: unknown): { id: number }[] => {
  return array(value, parseId);
};
export const parseObject = (value: unknown): Record<string, unknown> => {
  return record(value);
};
export const parseImport = (
  value: unknown,
): {
  inserted: number;
  skipped: number;
  allocationsInserted: number;
  allocationsPending: number;
} => {
  const item = record(value);
  return {
    inserted: integer(item.inserted),
    skipped: integer(item.skipped),
    allocationsInserted: integer(item.allocationsInserted ?? 0),
    allocationsPending: integer(item.allocationsPending ?? 0),
  };
};
export const parseReplanEvents = (value: unknown): ReplanEvent[] =>
  array(value, (entry) => {
    const item = record(entry);
    exactKeys(item, [
      "id",
      "triggerType",
      "triggerDetails",
      "sourcePlanVersion",
      "proposedPlanVersion",
      "userDecision",
      "createdAt",
      "decidedAt",
    ]);
    const proposed = item.proposedPlanVersion === null ? null : record(item.proposedPlanVersion);
    return {
      id: integer(item.id),
      triggerType: enumValue(item.triggerType, [
        "MONTHLY_REGULAR",
        "LARGE_UNEXPECTED_TRANSACTION",
        "CUMULATIVE_OVERSPENDING",
        "INCOME_CHANGED",
        "FIXED_COST_CHANGED",
        "SCHEDULED_EXPENSE_CHANGED",
        "GOAL_AMOUNT_CHANGED",
        "GOAL_DATE_CHANGED",
        "USER_REQUESTED",
      ]),
      userDecision:
        item.userDecision === null
          ? null
          : enumValue(item.userDecision, ["ACCEPT_NEW_PLAN", "KEEP_CURRENT_PLAN"]),
      createdAt: dateTime(item.createdAt),
      proposedPlanVersionId: proposed === null ? null : integer(proposed.id),
    };
  });

const date = (value: unknown): string => {
  const parsed = string(value);
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(parsed);
  if (!match) throw new Error("INVALID_RESPONSE");
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (day < 1 || day > (days[month - 1] ?? 0)) throw new Error("INVALID_RESPONSE");
  return parsed;
};
const yearMonth = (value: unknown): string => {
  const parsed = string(value);
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(parsed)) throw new Error("INVALID_RESPONSE");
  return parsed;
};
const uri = (value: unknown): string => {
  const parsed = string(value);
  let url: URL;
  try {
    url = new URL(parsed);
  } catch {
    throw new Error("INVALID_RESPONSE");
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error("INVALID_RESPONSE");
  return parsed;
};
const signedMoney = (value: unknown): number => integer(value);
export const parseUserProfile = (value: unknown): UserProfile => {
  const item = record(value);
  return {
    birthDate: item.birthDate === null ? null : date(item.birthDate),
    regionCode: nullableString(item.regionCode),
    updatedAt: dateTime(item.updatedAt),
  };
};
export const parseFinancialProfile = (value: unknown): FinancialProfile => {
  const item = record(value);
  return {
    monthlyIncome: money(item.monthlyIncome),
    monthlyFixedCost: money(item.monthlyFixedCost),
    updatedAt: dateTime(item.updatedAt),
  };
};
const parseGoal = (value: unknown): Goal => {
  const item = record(value);
  return {
    id: integer(item.id),
    name: string(item.name),
    targetAmount: money(item.targetAmount),
    currentSavedAmount: money(item.currentSavedAmount),
    targetDate: date(item.targetDate),
    status: enumValue(item.status, ["ACTIVE", "ACHIEVED", "CANCELLED"]),
    remainingMonths: integer(item.remainingMonths),
  };
};
export const parseGoalDetail = parseGoal;
export const parseGoals = (value: unknown): Goal[] => array(value, parseGoal);
const parsePlanVersionSummary = (value: unknown): PlanVersionSummary => {
  const item = record(value);
  return {
    id: integer(item.id),
    versionNo: integer(item.versionNo),
    generationType: enumValue(item.generationType, [
      "INITIAL",
      "MONTHLY_REGULAR",
      "TRIGGERED_REPLAN",
      "USER_REQUESTED",
    ]),
    status: enumValue(item.status, [
      "PROPOSED",
      "ACTIVE",
      "SUPERSEDED",
      "REJECTED",
      "INFEASIBLE",
      "STALE",
    ]),
    asOfDate: date(item.asOfDate),
    infeasibleReason: nullableString(item.infeasibleReason),
    createdAt: dateTime(item.createdAt),
    activatedAt: item.activatedAt === null ? null : dateTime(item.activatedAt),
  };
};
export const parsePlanVersionSummaries = (value: unknown): PlanVersionSummary[] =>
  array(value, parsePlanVersionSummary);
export const parseScheduledExpense = (value: unknown): ScheduledExpense => {
  const item = record(value);
  return {
    id: integer(item.id),
    name: string(item.name),
    amount: money(item.amount),
    scheduledDate: date(item.scheduledDate),
    status: enumValue(item.status, ["PLANNED", "COMPLETED", "CANCELLED"]),
    matchedTransactionCount: integer(item.matchedTransactionCount),
    matchedAmount: money(item.matchedAmount),
    triggeredReplanEventId: nullableInteger(item.triggeredReplanEventId),
  };
};
export const parseScheduledExpenses = (value: unknown): ScheduledExpense[] =>
  array(value, parseScheduledExpense);
export const parseDemoTransactionsResponse = (value: unknown): DemoTransactionsResponse => {
  const item = record(value);
  exactKeys(item, ["testerId", "scenarioVersion", "inserted", "completeMonths"]);
  return {
    testerId: enumValue(item.testerId, ["youth", "middle", "senior"]),
    scenarioVersion: integer(item.scenarioVersion),
    inserted: integer(item.inserted),
    completeMonths: integer(item.completeMonths),
  };
};
export const parseTransactionPage = (value: unknown): TransactionPage => {
  const page = record(value);
  return {
    items: array(page.items, (entry) => {
      const item = record(entry);
      return {
        id: integer(item.id),
        transactionAt: dateTime(item.transactionAt),
        amount: money(item.amount),
        transactionType: enumValue(item.transactionType, ["PAYMENT", "REFUND"]),
        category: string(item.category),
        merchantName: nullableString(item.merchantName),
        sourceId: string(item.sourceId),
        externalTransactionId: nullableString(item.externalTransactionId),
        refundStatus: enumValue(item.refundStatus, [
          "NOT_APPLICABLE",
          "UNMATCHED",
          "PENDING",
          "PARTIALLY_LINKED",
          "LINKED",
        ]),
      };
    }),
    nextCursor: nullableString(page.nextCursor),
  };
};
export const parseMonthlySpending = (value: unknown): MonthlySpending[] =>
  array(value, (entry) => {
    const item = record(entry);
    return {
      yearMonth: date(item.yearMonth),
      totalVariableSpending: signedMoney(item.totalVariableSpending),
      grossPaymentSpending: signedMoney(item.grossPaymentSpending),
      linkedRefundAmount: signedMoney(item.linkedRefundAmount),
      unmatchedRefundInflow: signedMoney(item.unmatchedRefundInflow),
      adjustedConsumption: signedMoney(item.adjustedConsumption),
      netCashFlow: signedMoney(item.netCashFlow),
      bootstrapEligibleSpending: money(item.bootstrapEligibleSpending),
    };
  });
export const parseCategorySpending = (value: unknown): CategorySpending => {
  const item = record(value);
  return {
    months: integer(item.months),
    currentAvgVariableSpending: signedMoney(item.currentAvgVariableSpending),
    categories: array(item.categories, (entry) => {
      const category = record(entry);
      return { category: string(category.category), monthlyAverage: signedMoney(category.monthlyAverage) };
    }),
  };
};
const parsePolicySource = (value: unknown): PolicySource => {
  const item = record(value);
  const locators = array(item.locators, string);
  if (!locators.length) throw new Error("INVALID_RESPONSE");
  return {
    organization: string(item.organization),
    officialUrl: uri(item.officialUrl),
    sourceVersion: string(item.sourceVersion),
    lastVerifiedAt: dateTime(item.lastVerifiedAt),
    locators,
  };
};
const parsePolicyResult = (value: unknown): PolicyResult => {
  const item = record(value);
  const policyVersionId = integer(item.policyVersionId);
  if (policyVersionId < 1) throw new Error("INVALID_RESPONSE");
  return {
    policyVersionId,
    title: string(item.title),
    summary: string(item.summary),
    planConnection: string(item.planConnection),
    supportDetails: string(item.supportDetails),
    confirmedConditions: array(item.confirmedConditions, string),
    additionalChecks: array(item.additionalChecks, string),
    applicationPeriod: string(item.applicationPeriod),
    asOfDate: date(item.asOfDate),
    eligibilityStatus: enumValue(item.eligibilityStatus, ["NEEDS_CONFIRMATION"]),
    calculationMode: enumValue(item.calculationMode, [
      "INFORMATIONAL",
      "ELIGIBILITY_ONLY",
      "ONE_TIME_FUNDING",
      "MONTHLY_EXPENSE_REDUCTION",
    ]),
    source: parsePolicySource(item.source),
  };
};
export const parsePolicySearchResponse = (value: unknown): PolicySearchResponse => {
  const item = record(value);
  if (item.type === "QUESTION") {
    const question = record(item.question);
    const options = array(question.options, (entry) => {
      const option = record(entry);
      return { value: string(option.value), label: string(option.label) };
    });
    if (!options.length) throw new Error("INVALID_RESPONSE");
    return {
      type: "QUESTION",
      question: { questionId: string(question.questionId), label: string(question.label), options },
    };
  }
  if (item.type === "RESULTS") {
    const results = array(item.results, parsePolicyResult);
    if (results.length > 3) throw new Error("INVALID_RESPONSE");
    return { type: "RESULTS", results };
  }
  throw new Error("INVALID_RESPONSE");
};
const parsePolicyPlanSummary = (value: unknown): PolicyPlanSummary => {
  const item = record(value);
  const simulationCoverage = number(item.simulationCoverage);
  const requiredReductionRate = number(item.requiredReductionRate);
  if (simulationCoverage < 0 || simulationCoverage > 1 || requiredReductionRate > 1)
    throw new Error("INVALID_RESPONSE");
  return {
    recommendedMonthlySpending: money(item.recommendedMonthlySpending),
    requiredReductionRate,
    simulationCoverage,
  };
};
export const parsePolicyScenario = (value: unknown): PolicyScenario => {
  const item = record(value);
  const adjustment = record(item.adjustment);
  const type = enumValue(adjustment.type, ["ONE_TIME_FUNDING", "MONTHLY_EXPENSE_REDUCTION"]);
  exactKeys(
    adjustment,
    type === "MONTHLY_EXPENSE_REDUCTION"
      ? ["type", "amountWon", "startYearMonth", "endYearMonth", "sourceVersion"]
      : ["type", "amountWon", "startYearMonth", "sourceVersion"],
  );
  return {
    currentPlanSummary: parsePolicyPlanSummary(item.currentPlanSummary),
    assumedPlanSummary: parsePolicyPlanSummary(item.assumedPlanSummary),
    adjustment: {
      type,
      amountWon: positiveMoney(adjustment.amountWon),
      startYearMonth: yearMonth(adjustment.startYearMonth),
      ...(type === "MONTHLY_EXPENSE_REDUCTION"
        ? { endYearMonth: yearMonth(adjustment.endYearMonth) }
        : {}),
      sourceVersion: string(adjustment.sourceVersion),
    },
    assumptionNotice: string(item.assumptionNotice),
    source: parsePolicySource(item.source),
  };
};
