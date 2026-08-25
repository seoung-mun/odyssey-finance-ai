import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ApiError } from "./api";
import { DashboardPage } from "./DashboardPage";
import type { Dashboard } from "./types";

const dashboard: Dashboard = {
  goal: {
    id: 1,
    name: "나만의 작업실",
    targetAmount: 30_000_000,
    currentSavedAmount: 8_400_000,
    targetDate: "2028-12-31",
    remainingMonths: 28,
  },
  activePlan: {
    id: 3,
    status: "ACTIVE",
    infeasibleReason: null,
    snapshot: { resolvedSpendingFloor: { mode: "AUTO", effectiveMonthlyAmount: 820_000 } },
    options: [],
    explanation: { status: "FALLBACK", text: "현재 계획의 핵심 수치를 확인해 주세요." },
  },
  selectedOption: {
    id: 7,
    optionType: "PRESET",
    nominalLevel: 0.8,
    recommendedMonthlySpending: 920_000,
    requiredReductionRate: 0.3185,
    simulationCoverage: 0.83,
    historicalFeasibilityRatio: 0.58,
    aggressiveWarning: false,
    effectiveMaxReductionRate: 0.3925,
    floorApplied: true,
    targetCoverageMet: true,
    percentileBands: [
      {
        monthIndex: 1,
        p10: 8_900_000,
        p25: 9_000_000,
        p50: 9_100_000,
        p75: 9_200_000,
        p90: 9_300_000,
      },
      {
        monthIndex: 28,
        p10: 27_000_000,
        p25: 29_000_000,
        p50: 31_000_000,
        p75: 33_000_000,
        p90: 35_000_000,
      },
    ],
  },
  pendingProposal: null,
  monthProgress: {
    plannedMonthlySpending: 920_000,
    actualToDate: 510_000,
    paceRatio: 0.92,
    daysElapsed: 18,
  },
};

const pendingDashboard = (): Dashboard => {
  if (!dashboard.activePlan) throw new Error("fixture requires an active plan");
  return {
    ...dashboard,
    activePlan: { ...dashboard.activePlan, explanation: { status: "PENDING", text: null } },
  };
};

it("renders the goal path and treats FALLBACK as an available explanation", async () => {
  const api = { get: vi.fn().mockResolvedValue(dashboard) };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("heading", { name: /나만의 작업실/ })).toBeInTheDocument();
  expect(screen.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeInTheDocument();
  expect(screen.getByText("기본 안내")).toBeInTheDocument();
  expect(screen.queryByText(/계산 실패/)).not.toBeInTheDocument();
});

it("refetches once after a 409 conflict", async () => {
  const api = {
    get: vi.fn().mockRejectedValueOnce(new ApiError(409, "STALE")).mockResolvedValueOnce(dashboard),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  expect(await screen.findByText("최신 상태를 불러왔습니다.")).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledTimes(2);
});

it("shows request ID and retries a server error", async () => {
  const api = {
    get: vi
      .fn()
      .mockRejectedValueOnce(new ApiError(500, "INTERNAL", undefined, "req-500"))
      .mockResolvedValueOnce(dashboard),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  expect(await screen.findByText(/req-500/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
});

it("shows the final error when the refetch after a 409 also fails", async () => {
  const api = {
    get: vi
      .fn()
      .mockRejectedValueOnce(new ApiError(409, "STALE"))
      .mockRejectedValueOnce(new ApiError(503, "CALCULATION_UNAVAILABLE", undefined, "req-503")),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  expect(await screen.findByText(/req-503/)).toBeInTheDocument();
});

it.each([
  { status: 403, destination: "권한 오류 화면" },
  { status: 404, destination: "찾을 수 없음 화면" },
])("routes $status to its dedicated screen", async ({ status, destination }) => {
  const api = { get: vi.fn().mockRejectedValue(new ApiError(status, "ERROR")) };
  render(
    <MemoryRouter
      initialEntries={["/dashboard"]}
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
    >
      <Routes>
        <Route path="/dashboard" element={<DashboardPage api={api} />} />
        <Route path="/forbidden" element={<h1>권한 오류 화면</h1>} />
        <Route path="/not-found" element={<h1>찾을 수 없음 화면</h1>} />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByRole("heading", { name: destination })).toBeInTheDocument();
});

it("polls a pending explanation and replaces it with READY", async () => {
  vi.useFakeTimers();
  try {
    const pending = pendingDashboard();
    const api = {
      get: vi
        .fn()
        .mockResolvedValueOnce(pending)
        .mockResolvedValueOnce({ status: "READY", text: "확정된 계획 설명입니다." }),
    };
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <DashboardPage api={api} />
      </MemoryRouter>,
    );
    await act(async () => undefined);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText("확정된 계획 설명입니다.")).toBeInTheDocument();
    expect(api.get).toHaveBeenLastCalledWith("/plan-versions/3/explanation", expect.any(Function));
  } finally {
    vi.useRealTimers();
  }
});

it("stops explanation polling after unmount", async () => {
  vi.useFakeTimers();
  try {
    const pending = pendingDashboard();
    const api = { get: vi.fn().mockResolvedValue(pending) };
    const view = render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <DashboardPage api={api} />
      </MemoryRouter>,
    );
    await act(async () => undefined);
    view.unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(api.get).toHaveBeenCalledTimes(1);
  } finally {
    vi.useRealTimers();
  }
});

it("limits polling across PENDING and PROCESSING transitions", async () => {
  vi.useFakeTimers();
  try {
    const pending = pendingDashboard();
    let processing = false;
    const api = {
      get: vi
        .fn()
        .mockResolvedValueOnce(pending)
        .mockImplementation(() => {
          processing = !processing;
          return Promise.resolve({ status: processing ? "PROCESSING" : "PENDING", text: null });
        }),
    };
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <DashboardPage api={api} />
      </MemoryRouter>,
    );
    await act(async () => undefined);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(40_000);
    });
    expect(api.get).toHaveBeenCalledTimes(16);
    expect(screen.getByText(/설명 없이 계획을 확인해 주세요/)).toBeInTheDocument();
  } finally {
    vi.useRealTimers();
  }
});

it("shows when the selected plan misses its target coverage", async () => {
  if (!dashboard.selectedOption) throw new Error("fixture requires a selected option");
  const misses: Dashboard = {
    ...dashboard,
    selectedOption: { ...dashboard.selectedOption, targetCoverageMet: false },
  };
  const api = { get: vi.fn().mockResolvedValue(misses) };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("목표 안정성 수준에 미치지 못합니다");
});
