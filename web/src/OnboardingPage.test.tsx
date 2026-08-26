import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "./api";
import { OnboardingPage } from "./OnboardingPage";
import type { PlanVersion } from "./types";

const plan: PlanVersion = {
  id: 9,
  status: "PROPOSED",
  infeasibleReason: null,
  snapshot: { resolvedSpendingFloor: { mode: "OFF", effectiveMonthlyAmount: 0 } },
  explanation: { status: "PENDING", text: null },
  options: [0.7, 0.8, 0.9].map((nominalLevel, index) => ({
    id: 70 + index,
    optionType: "PRESET",
    nominalLevel,
    recommendedMonthlySpending: 1_200_000 - index * 100_000,
    requiredReductionRate: 0.1 + index * 0.05,
    simulationCoverage: nominalLevel,
    historicalFeasibilityRatio: 0.6 - index * 0.1,
    aggressiveWarning: index === 2,
    effectiveMaxReductionRate: 0.3,
    floorApplied: false,
    targetCoverageMet: true,
    percentileBands: [],
  })),
};

const openDirectForm = async () => {
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  await userEvent.type(screen.getByLabelText("월 소득"), "3600000");
  await userEvent.type(screen.getByLabelText("월 고정비"), "1100000");
  await userEvent.type(screen.getByLabelText("목표 이름"), "작업실");
  await userEvent.type(screen.getByLabelText("목표 금액"), "30000000");
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2028-12-31");
};

const fillTransactions = async () => {
  const dates = screen.getAllByLabelText(/거래일/);
  const amounts = screen.getAllByLabelText(/거래 금액/);
  const categories = screen.getAllByLabelText(/거래 분류/);
  for (let index = 0; index < 3; index += 1) {
    await userEvent.type(dates[index], `2026-0${index + 5}-10`);
    await userEvent.type(amounts[index], `${100000 + index}`);
    await userEvent.type(categories[index], "생활");
  }
};

it("keeps entered values when the network fails", async () => {
  const api = {
    get: vi.fn(),
    patch: vi.fn(),
    put: vi.fn().mockRejectedValue(new ApiError(0, "NETWORK_ERROR")),
    post: vi.fn(),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  await userEvent.type(screen.getByLabelText("월 소득"), "3600000");
  await userEvent.type(screen.getByLabelText("월 고정비"), "1100000");
  await userEvent.type(screen.getByLabelText("목표 이름"), "작업실");
  await userEvent.type(screen.getByLabelText("목표 금액"), "30000000");
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2028-12-31");
  await fillTransactions();
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("입력값은 그대로 보관했습니다");
  expect(screen.getByLabelText("목표 이름")).toHaveValue("작업실");
});

it("loads sample data only after an explicit choice", async () => {
  const api = {
    get: vi.fn().mockResolvedValue({ activeGoalId: 41 }),
    patch: vi.fn(),
    put: vi.fn(),
    post: vi.fn(async (path: string) => (path.includes("plan-versions") ? plan : { loaded: true })),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  expect(api.post).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: "샘플로 둘러보기" }));
  expect(api.post).toHaveBeenCalledWith("/me/sample-data", undefined, expect.any(Function));
});

it("blocks a rapid duplicate sample request", () => {
  const api = {
    get: vi.fn(),
    patch: vi.fn(),
    put: vi.fn(),
    post: vi.fn().mockReturnValue(new Promise(() => undefined)),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  const button = screen.getByRole("button", { name: "샘플로 둘러보기" });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(api.post).toHaveBeenCalledOnce();
});

it("retries only plan creation after the goal was already saved", async () => {
  let planAttempts = 0;
  const api = {
    get: vi.fn(),
    patch: vi.fn(),
    put: vi.fn().mockResolvedValue({}),
    post: vi.fn(async (path: string) => {
      if (path === "/transactions/import") return { inserted: 3, skipped: 0 };
      if (path === "/goals") return { id: 41 };
      planAttempts += 1;
      if (planAttempts === 1) throw new ApiError(503, "CALCULATION_UNAVAILABLE");
      return plan;
    }),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  await userEvent.type(screen.getByLabelText("월 소득"), "3600000");
  await userEvent.type(screen.getByLabelText("월 고정비"), "1100000");
  await userEvent.type(screen.getByLabelText("목표 이름"), "작업실");
  await userEvent.type(screen.getByLabelText("목표 금액"), "30000000");
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2028-12-31");
  await fillTransactions();
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("입력값은 그대로 보관했습니다");
  await userEvent.click(screen.getByRole("button", { name: "계획 다시 계산하기" }));

  expect(api.post).toHaveBeenCalledWith("/goals", expect.anything(), expect.any(Function));
  expect(api.post).toHaveBeenCalledWith(
    "/goals/41/plan-versions",
    { generationType: "INITIAL" },
    expect.any(Function),
  );
  expect(api.post.mock.calls.filter((call) => call[0] === "/goals")).toHaveLength(1);
});

it("refetches state after a financial profile 409 and resumes the existing goal", async () => {
  const api = {
    get: vi.fn(async (path: string) =>
      path === "/me" ? { activeGoalId: 41 } : { monthlyIncome: 3_600_000 },
    ),
    put: vi.fn(async (path: string) => {
      if (path === "/me/financial-profile") throw new ApiError(409, "PROPOSED_PLAN_EXISTS");
      return {};
    }),
    post: vi.fn(async (path: string) => (path.includes("plan-versions") ? plan : {})),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(
    await screen.findByRole("heading", { name: "유지할 수 있는 항로를 고르세요" }),
  ).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("starts a new empty account with spending floor OFF", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  expect(screen.getByLabelText("생활비 하한")).toHaveValue("OFF");
});

it("imports three months of transactions before showing the three plan options", async () => {
  const api = {
    get: vi.fn(),
    patch: vi.fn(),
    put: vi.fn().mockResolvedValue({}),
    post: vi.fn(async (path: string) =>
      path === "/goals"
        ? { id: 41 }
        : path.includes("plan-versions")
          ? plan
          : path === "/transactions/import"
            ? { inserted: 3, skipped: 0 }
            : {},
    ),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(
    await screen.findByRole("heading", { name: "유지할 수 있는 항로를 고르세요" }),
  ).toBeInTheDocument();
  expect(api.post).toHaveBeenCalledWith(
    "/transactions/import",
    {
      transactions: expect.arrayContaining([
        expect.objectContaining({
          transactionAt: "2026-05-10T00:00:00+09:00",
          amount: 100000,
          sourceId: "MANUAL",
          externalTransactionId: expect.stringMatching(/^manual-/),
        }),
      ]),
    },
    expect.any(Function),
  );
  expect(screen.getAllByRole("button", { name: /계획 선택/ })).toHaveLength(3);
});

it("loads sample data into the same plan comparison flow", async () => {
  const api = {
    get: vi.fn().mockResolvedValue({ activeGoalId: 41 }),
    patch: vi.fn(),
    put: vi.fn(),
    post: vi.fn(async (path: string) =>
      path.includes("plan-versions")
        ? plan
        : { loaded: true, sampleDataLoadedAt: "2026-08-25T00:00:00+09:00" },
    ),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "샘플로 둘러보기" }));

  expect(
    await screen.findByRole("heading", { name: "유지할 수 있는 항로를 고르세요" }),
  ).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledWith("/me", expect.any(Function));
  expect(api.post).toHaveBeenCalledWith(
    "/goals/41/plan-versions",
    { generationType: "INITIAL" },
    expect.any(Function),
  );
});

it("rejects a plan response missing one of the 70/80/90 options", async () => {
  const incompletePlan: PlanVersion = { ...plan, options: plan.options.slice(0, 2) };
  const api = {
    get: vi.fn().mockResolvedValue({ activeGoalId: 41 }),
    put: vi.fn(),
    post: vi.fn(async (path: string) =>
      path.includes("plan-versions") ? incompletePlan : { loaded: true },
    ),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "샘플로 둘러보기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("70/80/90");
  expect(
    screen.queryByRole("heading", { name: "유지할 수 있는 항로를 고르세요" }),
  ).not.toBeInTheDocument();
});

it("rejects unsafe money before sending it", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  await userEvent.clear(screen.getByLabelText("목표 금액"));
  await userEvent.type(screen.getByLabelText("목표 금액"), "9007199254740992");
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("안전하게 처리할 수 있는 정수 범위");
  expect(api.put).not.toHaveBeenCalled();
});

it("requires transactions from three distinct months", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  const dates = screen.getAllByLabelText(/거래일/);
  const amounts = screen.getAllByLabelText(/거래 금액/);
  const categories = screen.getAllByLabelText(/거래 분류/);
  for (let index = 0; index < 3; index += 1) {
    await userEvent.type(dates[index], `2026-05-${10 + index}`);
    await userEvent.type(amounts[index], "100000");
    await userEvent.type(categories[index], "생활");
  }
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("서로 다른 3개월");
  expect(api.put).not.toHaveBeenCalled();
});

it("rejects a whitespace goal name before sending it", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  await userEvent.clear(screen.getByLabelText("목표 이름"));
  await userEvent.type(screen.getByLabelText("목표 이름"), "   ");
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("목표 이름을 입력");
  expect(api.put).not.toHaveBeenCalled();
});

it("rejects a transaction from the current incomplete month", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  const currentMonth = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).format(new Date());
  const firstDate = screen.getAllByLabelText(/거래일/)[0];
  await userEvent.clear(firstDate);
  await userEvent.type(firstDate, `${currentMonth}-10`);
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("완전히 끝난 달");
  expect(api.put).not.toHaveBeenCalled();
});

it("rejects an impossible calendar date after input type is bypassed", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  const date = screen.getAllByLabelText(/거래일/)[0];
  date.setAttribute("type", "text");
  fireEvent.change(date, { target: { value: "2026-02-31" } });
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("유효한 거래일");
  expect(api.put).not.toHaveBeenCalled();
});

it("rejects an impossible goal date after input type is bypassed", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  const date = screen.getByLabelText("목표 날짜");
  date.setAttribute("type", "text");
  fireEvent.change(date, { target: { value: "2028-02-31" } });
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("유효한 목표 날짜");
  expect(api.put).not.toHaveBeenCalled();
});

it("requires a future goal date and the CUSTOM floor amount", async () => {
  const api = { get: vi.fn(), patch: vi.fn(), put: vi.fn(), post: vi.fn() };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await openDirectForm();
  await fillTransactions();
  await userEvent.clear(screen.getByLabelText("목표 날짜"));
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2020-01-01");
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("오늘보다 미래");

  await userEvent.selectOptions(screen.getByLabelText("생활비 하한"), "CUSTOM");
  await userEvent.clear(screen.getByLabelText("목표 날짜"));
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2099-01-01");
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));
  expect(screen.getByLabelText("최소 월 유동지출")).toBeInvalid();
  expect(api.put).not.toHaveBeenCalled();
});

it("selects any preset option before moving to the dashboard", async () => {
  const api = {
    get: vi.fn().mockResolvedValue({ activeGoalId: 41 }),
    patch: vi.fn(),
    put: vi.fn(),
    post: vi.fn(async (path: string) =>
      path.includes("select-option")
        ? { ...plan, status: "ACTIVE" }
        : path.includes("plan-versions")
          ? plan
          : {},
    ),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "샘플로 둘러보기" }));
  await userEvent.click(await screen.findByRole("button", { name: /80% 계획 선택/ }));

  expect(api.post).toHaveBeenCalledWith(
    "/plan-versions/9/select-option",
    { planOptionId: 71 },
    expect.any(Function),
  );
});
