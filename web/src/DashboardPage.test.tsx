import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";
import { DashboardPage } from "./DashboardPage";

const dashboard = {
  goal: { id: 1, name: "나만의 작업실", targetAmount: 30_000_000, currentSavedAmount: 8_400_000, targetDate: "2028-12-31", status: "ACTIVE", remainingMonths: 28, spendingReplanSuppressedUntil: null, createdAt: "2026-08-01T00:00:00+09:00" },
  activePlan: {
    id: 3, versionNo: 2, generationType: "INITIAL", status: "ACTIVE", asOfDate: "2026-08-01", infeasibleReason: null, createdAt: "2026-08-01T00:00:00+09:00", activatedAt: "2026-08-01T00:00:00+09:00",
    snapshot: { monthlyIncome: 3_600_000, monthlyFixedCost: 1_100_000, targetAmount: 30_000_000, currentSaved: 8_400_000, targetDate: "2028-12-31", availableVariableBudget: 40_000_000, currentAvgVariableSpending: 1_350_000, remainingMonths: 28, resolvedSpendingFloor: { mode: "AUTO", requestedMonthlyAmount: 820_000, effectiveMonthlyAmount: 820_000, autoHistoryMonths: 12 } },
    options: [], simulation: { method: "IID_BOOTSTRAP", nPaths: 10000, engineVersion: "0.3.0", createdAt: "2026-08-01T00:00:00+09:00" },
    explanation: { status: "FALLBACK", text: "현재 계획의 핵심 수치를 확인해 주세요.", model: null, generatedAt: null, retryCount: 2, promptVersion: "v1", failedNumbers: [] },
  },
  selectedOption: { id: 7, optionType: "PRESET", nominalLevel: 0.8, recommendedMonthlySpending: 920_000, requiredReductionRate: 0.3185, simulationCoverage: 0.83, historicalFeasibilityRatio: 0.58, aggressiveWarning: false, effectiveMaxReductionRate: 0.3925, floorApplied: true, targetCoverageMet: true, selectedAt: "2026-08-01T00:00:00+09:00", percentileBands: [
    { monthIndex: 1, metricType: "CUMULATIVE_SAVINGS", p10: 8_900_000, p25: 9_000_000, p50: 9_100_000, p75: 9_200_000, p90: 9_300_000 },
    { monthIndex: 28, metricType: "CUMULATIVE_SAVINGS", p10: 27_000_000, p25: 29_000_000, p50: 31_000_000, p75: 33_000_000, p90: 35_000_000 },
  ] },
  pendingProposal: null,
  monthProgress: { yearMonth: "2026-08-01", plannedMonthlySpending: 920_000, actualToDate: 510_000, paceRatio: 0.92, daysElapsed: 18 },
};

it("renders the goal path and treats FALLBACK as an available explanation", async () => {
  const api = { get: vi.fn().mockResolvedValue(dashboard) } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><DashboardPage api={api} /></MemoryRouter>);
  expect(await screen.findByRole("heading", { name: /나만의 작업실/ })).toBeInTheDocument();
  expect(screen.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeInTheDocument();
  expect(screen.getByText("기본 안내")).toBeInTheDocument();
  expect(screen.queryByText(/계산 실패/)).not.toBeInTheDocument();
});

it("refetches once after a 409 conflict", async () => {
  const api = { get: vi.fn().mockRejectedValueOnce(new ApiError(409, "STALE")).mockResolvedValueOnce(dashboard) } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><DashboardPage api={api} /></MemoryRouter>);
  expect(await screen.findByText("최신 상태를 불러왔습니다.")).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledTimes(2);
});

it("shows request ID and retries a server error", async () => {
  const api = { get: vi.fn().mockRejectedValueOnce(new ApiError(500, "INTERNAL", undefined, "req-500")).mockResolvedValueOnce(dashboard) } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><DashboardPage api={api} /></MemoryRouter>);
  expect(await screen.findByText(/req-500/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
});

it("shows the final error when the refetch after a 409 also fails", async () => {
  const api = { get: vi.fn().mockRejectedValueOnce(new ApiError(409, "STALE")).mockRejectedValueOnce(new ApiError(503, "CALCULATION_UNAVAILABLE", undefined, "req-503")) } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><DashboardPage api={api} /></MemoryRouter>);
  expect(await screen.findByText(/req-503/)).toBeInTheDocument();
});
