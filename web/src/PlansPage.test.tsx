import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "./api";
import { PlansPage } from "./PlansPage";

const goal = {
  id: 1,
  name: "독립 자금",
  targetAmount: 30_000_000,
  currentSavedAmount: 8_000_000,
  targetDate: "2028-12-31",
  status: "ACTIVE",
  remainingMonths: 28,
  spendingReplanSuppressedUntil: null,
  createdAt: "2026-08-20T10:00:00+09:00",
  triggeredReplanEventId: null,
};

const option = {
  id: 21,
  optionType: "PRESET",
  nominalLevel: 0.8,
  recommendedMonthlySpending: 1_200_000,
  requiredReductionRate: 0.2,
  simulationCoverage: 0.8,
  historicalFeasibilityRatio: 0.6,
  aggressiveWarning: false,
  targetCoverageMet: true,
  percentileBands: [],
};

const activePlan = {
  id: 3,
  versionNo: 3,
  generationType: "INITIAL",
  status: "ACTIVE",
  asOfDate: "2026-08-31",
  createdAt: "2026-08-31T10:00:00+09:00",
  activatedAt: "2026-08-31T10:01:00+09:00",
  infeasibleReason: null,
  explanation: { status: "READY", text: "확정된 계획 설명입니다." },
  options: [option],
};

const proposal = {
  ...activePlan,
  id: 11,
  versionNo: 4,
  generationType: "USER_REQUESTED",
  status: "PROPOSED",
  options: [{ ...option, id: 31 }],
};

const summary = (plan: typeof activePlan) => ({
  id: plan.id,
  versionNo: plan.versionNo,
  generationType: plan.generationType,
  status: plan.status,
  asOfDate: plan.asOfDate,
  createdAt: plan.createdAt,
  activatedAt: plan.activatedAt,
  infeasibleReason: plan.infeasibleReason,
});

const renderPage = (api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn> }) =>
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <PlansPage api={api} />
    </MemoryRouter>,
  );

it("loads the active goal's version route and detail", async () => {
  const api = {
    get: vi.fn(async (path: string) => {
      if (path === "/me") return { activeGoalId: 1 };
      if (path === "/goals") return [goal];
      if (path === "/goals/1/plan-versions") return [summary(activePlan)];
      if (path === "/plan-versions/3") return activePlan;
      if (path === "/plan-versions/3/explanation") return activePlan.explanation;
      if (path === "/goals/1/replan-events") return [];
      throw new Error(`unexpected GET ${path}`);
    }),
    post: vi.fn(),
  };
  renderPage(api);

  expect(await screen.findByRole("heading", { name: "독립 자금 계획" })).toBeInTheDocument();
  expect(screen.getByText("확정된 계획 설명입니다.")).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledWith("/plan-versions/3", expect.any(Function));
});

it("does not select an unaccepted replan proposal", async () => {
  const api = {
    get: vi.fn(async (path: string) => {
      if (path === "/me") return { activeGoalId: 1 };
      if (path === "/goals") return [goal];
      if (path === "/goals/1/plan-versions") return [summary(proposal), summary(activePlan)];
      if (path === "/plan-versions/11") return proposal;
      if (path === "/plan-versions/11/explanation") return proposal.explanation;
      if (path === "/goals/1/replan-events")
        return [
          {
            id: 44,
            triggerType: "USER_REQUESTED",
            userDecision: null,
            createdAt: "2026-08-31T10:00:00+09:00",
            proposedPlanVersion: { id: 11 },
          },
        ];
      throw new Error(`unexpected GET ${path}`);
    }),
    post: vi.fn(async (path: string) => {
      if (path === "/replan-events/44/decision") throw new ApiError(503, "DECISION_UNAVAILABLE");
      throw new Error(`unexpected POST ${path}`);
    }),
  };
  renderPage(api);

  await userEvent.click(await screen.findByRole("button", { name: "80% 제안 선택" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("새 계획 수락을 완료하지 못했습니다");
  expect(api.post).not.toHaveBeenCalledWith(
    "/plan-versions/11/select-option",
    expect.anything(),
    expect.any(Function),
  );
});

it("accepts a proposal before selecting its option", async () => {
  const api = {
    get: vi.fn(async (path: string) => {
      if (path === "/me") return { activeGoalId: 1 };
      if (path === "/goals") return [goal];
      if (path === "/goals/1/plan-versions") return [summary(proposal), summary(activePlan)];
      if (path === "/plan-versions/11") return proposal;
      if (path === "/plan-versions/11/explanation") return proposal.explanation;
      if (path === "/goals/1/replan-events")
        return [
          {
            id: 44,
            triggerType: "USER_REQUESTED",
            userDecision: null,
            createdAt: "2026-08-31T10:00:00+09:00",
            proposedPlanVersion: { id: 11 },
          },
        ];
      throw new Error(`unexpected GET ${path}`);
    }),
    post: vi.fn(async (path: string) => {
      if (path === "/replan-events/44/decision") return { id: 44 };
      if (path === "/plan-versions/11/select-option") return { ...proposal, status: "ACTIVE" };
      throw new Error(`unexpected POST ${path}`);
    }),
  };
  renderPage(api);

  await userEvent.click(await screen.findByRole("button", { name: "80% 제안 선택" }));

  await waitFor(() =>
    expect(api.post).toHaveBeenCalledWith(
      "/plan-versions/11/select-option",
      { planOptionId: 31 },
      expect.any(Function),
    ),
  );
  expect(api.post.mock.invocationCallOrder[0]).toBeLessThan(api.post.mock.invocationCallOrder[1]);
});

it("shows an empty route and creates the first plan", async () => {
  let created = false;
  const api = {
    get: vi.fn(async (path: string) => {
      if (path === "/me") return { activeGoalId: 1 };
      if (path === "/goals") return [goal];
      if (path === "/goals/1/plan-versions") return created ? [summary(activePlan)] : [];
      if (path === "/goals/1/replan-events") return [];
      if (path === "/plan-versions/3") return activePlan;
      if (path === "/plan-versions/3/explanation") return activePlan.explanation;
      throw new Error(`unexpected GET ${path}`);
    }),
    post: vi.fn(async (path: string) => {
      if (path === "/goals/1/plan-versions") {
        created = true;
        return activePlan;
      }
      throw new Error(`unexpected POST ${path}`);
    }),
  };
  renderPage(api);
  expect(await screen.findByText("아직 만든 계획이 없습니다.")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "첫 계획 만들기" }));
  await waitFor(() =>
    expect(api.post).toHaveBeenCalledWith(
      "/goals/1/plan-versions",
      { generationType: "INITIAL" },
      expect.any(Function),
    ),
  );
  expect(await screen.findByText("확정된 계획 설명입니다.")).toBeInTheDocument();
});

it("loads the selected historical version detail and explanation", async () => {
  const older = {
    ...activePlan,
    id: 2,
    versionNo: 2,
    status: "SUPERSEDED",
    explanation: { status: "READY", text: "이전 계획 설명입니다." },
  };
  const api = {
    get: vi.fn(async (path: string) => {
      if (path === "/me") return { activeGoalId: 1 };
      if (path === "/goals") return [goal];
      if (path === "/goals/1/plan-versions") return [summary(activePlan), summary(older)];
      if (path === "/plan-versions/3") return activePlan;
      if (path === "/plan-versions/3/explanation") return activePlan.explanation;
      if (path === "/plan-versions/2") return older;
      if (path === "/plan-versions/2/explanation") return older.explanation;
      if (path === "/goals/1/replan-events") return [];
      throw new Error(`unexpected GET ${path}`);
    }),
    post: vi.fn(),
  };
  renderPage(api);
  await userEvent.click(await screen.findByRole("button", { name: "v2 계획 보기" }));
  expect(await screen.findByText("이전 계획 설명입니다.")).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledWith("/plan-versions/2", expect.any(Function));
  expect(api.get).toHaveBeenCalledWith("/plan-versions/2/explanation", expect.any(Function));
});
