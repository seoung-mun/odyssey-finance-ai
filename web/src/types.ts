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
  recommendedMonthlySpending: number;
  requiredReductionRate: number;
  simulationCoverage: number;
  historicalFeasibilityRatio: number;
  aggressiveWarning: boolean;
  floorApplied: boolean;
  percentileBands: PercentileBand[];
};

export type PlanVersion = {
  id: number;
  status: "PROPOSED" | "ACTIVE" | "SUPERSEDED" | "REJECTED" | "INFEASIBLE" | "STALE";
  infeasibleReason: string | null;
  explanation: Explanation;
  snapshot: {
    resolvedSpendingFloor: {
      mode: "OFF" | "AUTO" | "CUSTOM";
      effectiveMonthlyAmount: number;
    };
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
