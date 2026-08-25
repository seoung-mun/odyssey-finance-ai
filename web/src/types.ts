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
  effectiveMaxReductionRate: number;
  floorApplied: boolean;
  targetCoverageMet: boolean;
  percentileBands: PercentileBand[];
};
export type PlanVersion = {
  id: number;
  status: "PROPOSED" | "ACTIVE" | "SUPERSEDED" | "REJECTED" | "INFEASIBLE" | "STALE";
  infeasibleReason: string | null;
  explanation: Explanation;
  options: PlanOption[];
  snapshot: {
    resolvedSpendingFloor: { mode: "OFF" | "AUTO" | "CUSTOM"; effectiveMonthlyAmount: number };
  };
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
  activePlan: PlanVersion | null;
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

type RecordValue = Record<string, unknown>;

const record = (value: unknown): RecordValue => {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    throw new Error("INVALID_RESPONSE");
  return Object.fromEntries(Object.entries(value));
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
    p10: money(item.p10),
    p25: money(item.p25),
    p50: money(item.p50),
    p75: money(item.p75),
    p90: money(item.p90),
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
  const effectiveMaxReductionRate = number(item.effectiveMaxReductionRate);
  const nominalLevel = item.nominalLevel === null ? null : number(item.nominalLevel);
  if (
    coverage < 0 ||
    coverage > 1 ||
    feasibility < 0 ||
    feasibility > 1 ||
    effectiveMaxReductionRate < 0 ||
    effectiveMaxReductionRate > 1 ||
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
    effectiveMaxReductionRate,
    floorApplied: boolean(item.floorApplied),
    targetCoverageMet: boolean(item.targetCoverageMet),
    percentileBands: array(item.percentileBands, parseBand),
  };
};
export const parsePlanVersion = (value: unknown): PlanVersion => {
  const item = record(value);
  const snapshot = record(item.snapshot);
  const floor = record(snapshot.resolvedSpendingFloor);
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
    snapshot: {
      resolvedSpendingFloor: {
        mode: enumValue(floor.mode, ["OFF", "AUTO", "CUSTOM"]),
        effectiveMonthlyAmount: money(floor.effectiveMonthlyAmount),
      },
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
    activePlan: item.activePlan === null ? null : parsePlanVersion(item.activePlan),
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
export const parseId = (value: unknown): { id: number } => {
  return { id: integer(record(value).id) };
};
export const parsePlanSummaries = (value: unknown): { id: number }[] => {
  return array(value, parseId);
};
export const parseObject = (value: unknown): Record<string, unknown> => {
  return record(value);
};
export const parseSample = (value: unknown): { loaded: boolean; sampleDataLoadedAt: string } => {
  const item = record(value);
  return { loaded: boolean(item.loaded), sampleDataLoadedAt: dateTime(item.sampleDataLoadedAt) };
};
export const parseImport = (value: unknown): { inserted: number; skipped: number } => {
  const item = record(value);
  return { inserted: integer(item.inserted), skipped: integer(item.skipped) };
};
