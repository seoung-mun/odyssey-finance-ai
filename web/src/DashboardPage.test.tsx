import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

it("groups live goal, route, and current plan data into dashboard landmarks", async () => {
  const api = { get: vi.fn().mockResolvedValue(dashboard) };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );

  expect(await screen.findByRole("region", { name: "목표 요약" })).toHaveTextContent(
    "나만의 작업실",
  );
  expect(screen.getByRole("region", { name: "목표까지의 항로" })).toContainElement(
    screen.getByRole("img", { name: /목표까지의 저축 예상 범위/ }),
  );
  expect(screen.getByRole("region", { name: "현재 계획 지표" })).toHaveTextContent("₩920,000");
  expect(screen.getByRole("region", { name: "현재 계획 지표" })).toHaveTextContent("₩510,000");
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

it("recovers a lost ACCEPT_NEW_PLAN response only after confirming the same event", async () => {
  const proposal = {
    ...dashboard.activePlan!,
    id: 11,
    status: "PROPOSED" as const,
    options: [{ ...dashboard.selectedOption!, id: 21, nominalLevel: 0.8 }],
  };
  const api = {
    get: vi.fn(async (path: string) =>
      path.includes("replan-events")
        ? [
            {
              id: 31,
              triggerType: "USER_REQUESTED",
              userDecision: "ACCEPT_NEW_PLAN",
              createdAt: "2026-08-26T12:00:00+09:00",
              proposedPlanVersion: { id: 11 },
            },
          ]
        : dashboard,
    ),
    post: vi.fn(async (path: string) => {
      if (path.endsWith("/replan")) return { ...proposal, replanEventId: 31 };
      if (path.endsWith("/decision")) throw new ApiError(409, "DECISION_ALREADY_MADE");
      return { ...proposal, status: "ACTIVE" };
    }),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  await screen.findByRole("heading", { name: /나만의 작업실/ });
  await userEvent.click(screen.getByRole("button", { name: "지금 재계획하기" }));
  await userEvent.click(await screen.findByRole("button", { name: "80% 새 계획 선택" }));

  await waitFor(() =>
    expect(api.post).toHaveBeenCalledWith(
      "/plan-versions/11/select-option",
      { planOptionId: 21 },
      expect.any(Function),
    ),
  );
  expect(api.get).toHaveBeenCalledWith("/goals/1/replan-events", expect.any(Function));
});

it("does not treat an unrelated decision 409 as accepted", async () => {
  const proposal = {
    ...dashboard.activePlan!,
    id: 11,
    status: "PROPOSED" as const,
    options: [{ ...dashboard.selectedOption!, id: 21, nominalLevel: 0.8 }],
  };
  const api = {
    get: vi.fn(async (path: string) =>
      path.includes("replan-events")
        ? [
            {
              id: 31,
              triggerType: "USER_REQUESTED",
              userDecision: "KEEP_CURRENT_PLAN",
              createdAt: "2026-08-26T12:00:00+09:00",
              proposedPlanVersion: { id: 11 },
            },
          ]
        : dashboard,
    ),
    post: vi.fn(async (path: string) => {
      if (path.endsWith("/replan")) return { ...proposal, replanEventId: 31 };
      throw new ApiError(409, "DECISION_ALREADY_MADE");
    }),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  await screen.findByRole("heading", { name: /나만의 작업실/ });
  await userEvent.click(screen.getByRole("button", { name: "지금 재계획하기" }));
  await userEvent.click(await screen.findByRole("button", { name: "80% 새 계획 선택" }));

  await waitFor(() => expect(api.get).toHaveBeenCalledWith("/goals/1/replan-events", expect.any(Function)));
  expect(api.post).not.toHaveBeenCalledWith(
    "/plan-versions/11/select-option",
    expect.anything(),
    expect.any(Function),
  );
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

it("requests one replan, compares three options, and activates the selected option", async () => {
  const proposal = {
    ...dashboard.activePlan!,
    id: 11,
    status: "PROPOSED" as const,
    options: [0.7, 0.8, 0.9].map((nominalLevel, index) => ({
      ...dashboard.selectedOption!,
      id: 20 + index,
      nominalLevel,
    })),
  };
  let dashboardLoads = 0;
  const api = {
    get: vi.fn(async (path: string) => {
      if (path.includes("replan-events")) return [];
      dashboardLoads += 1;
      return dashboard;
    }),
    post: vi.fn(async (path: string) =>
      path.endsWith("/replan")
        ? { ...proposal, replanEventId: 31 }
        : path.endsWith("/decision")
          ? { id: 31, userDecision: "ACCEPT_NEW_PLAN" }
          : { ...proposal, status: "ACTIVE" },
    ),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  await screen.findByRole("heading", { name: /나만의 작업실/ });
  const request = screen.getByRole("button", { name: "지금 재계획하기" });
  fireEvent.click(request);
  fireEvent.click(request);
  expect(await screen.findAllByRole("button", { name: /새 계획 선택/ })).toHaveLength(3);
  expect(api.post.mock.calls.filter(([path]) => path === "/goals/1/replan")).toHaveLength(1);

  await userEvent.click(screen.getByRole("button", { name: "80% 새 계획 선택" }));
  await waitFor(() => expect(dashboardLoads).toBe(2));
  expect(api.post).toHaveBeenCalledWith(
    "/replan-events/31/decision",
    { decision: "ACCEPT_NEW_PLAN" },
    expect.any(Function),
  );
  expect(api.post).toHaveBeenCalledWith(
    "/plan-versions/11/select-option",
    { planOptionId: 21 },
    expect.any(Function),
  );
  expect(api.post.mock.invocationCallOrder[1]).toBeLessThan(api.post.mock.invocationCallOrder[2]);
});

it.each([
  { status: 422, message: "목표 금액이나 날짜를 조정" },
  { status: 503, message: "재계획하지 못했습니다" },
])("keeps the dashboard and explains a replan $status", async ({ status, message }) => {
  const api = {
    get: vi.fn().mockResolvedValue(dashboard),
    post: vi.fn().mockRejectedValue(new ApiError(status, "REPLAN_ERROR", undefined, "req-r")),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <DashboardPage api={api} />
    </MemoryRouter>,
  );
  await screen.findByRole("heading", { name: /나만의 작업실/ });
  await userEvent.click(screen.getByRole("button", { name: "지금 재계획하기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent(message);
  expect(screen.getByRole("heading", { name: /나만의 작업실/ })).toBeInTheDocument();
});

it.each([
  { status: 403, destination: "권한 오류 화면" },
  { status: 404, destination: "찾을 수 없음 화면" },
])("routes a replan $status to its dedicated screen", async ({ status, destination }) => {
  const api = {
    get: vi.fn().mockResolvedValue(dashboard),
    post: vi.fn().mockRejectedValue(new ApiError(status, "REPLAN_ERROR")),
  };
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
  await screen.findByRole("heading", { name: /나만의 작업실/ });
  await userEvent.click(screen.getByRole("button", { name: "지금 재계획하기" }));

  expect(await screen.findByRole("heading", { name: destination })).toBeInTheDocument();
});
