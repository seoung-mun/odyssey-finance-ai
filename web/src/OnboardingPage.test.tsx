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
    targetCoverageMet: true,
    percentileBands: [],
  })),
};

const demoTesters = ["변동 소비형", "균형 소비형", "안정 소비형"].map((displayName, index) => ({
  testerId: `tester-${index + 1}`,
  displayName,
  description: `${displayName} 시나리오`,
  ageGroup: (["YOUTH", "MIDDLE_AGED", "SENIOR"] as const)[index],
  monthlyIncome: 3_000_000 + index * 1_000_000,
  monthlyFixedCost: 1_000_000 + index * 100_000,
  goalName: `${displayName} 목표`,
  goalTargetAmount: 12_000_000 + index * 1_000_000,
  goalMonths: 12 + index,
}));

const seedResponse = {
  testerId: "tester-1",
  scenarioVersion: 1,
  seededAt: "2026-08-28T10:00:00+09:00",
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

it("loads and displays all three demo testers while keeping direct entry", async () => {
  const api = {
    get: vi.fn().mockResolvedValue(demoTesters),
    put: vi.fn(),
    post: vi.fn(),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );

  expect(screen.getByText("데모 항로를 불러오고 있습니다")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "변동 소비형으로 시작하기" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "균형 소비형으로 시작하기" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "안정 소비형으로 시작하기" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "직접 시작하기" })).toBeInTheDocument();
});

it("groups demo scenarios and direct entry into named start cards", async () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage
        api={{ get: vi.fn().mockResolvedValue(demoTesters), put: vi.fn(), post: vi.fn() }}
      />
    </MemoryRouter>,
  );

  expect(
    await screen.findByRole("heading", { name: "데모 시나리오를 선택하세요" }),
  ).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "내 정보로 시작하기" })).toBeInTheDocument();
  expect(
    screen.getByText("월 소득 · 고정비 · 목표 기간이 반영된 시나리오입니다."),
  ).toBeInTheDocument();
});

it("shows the four-step onboarding structure around the direct input form", async () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={{ get: vi.fn().mockResolvedValue([]), put: vi.fn(), post: vi.fn() }} />
    </MemoryRouter>,
  );
  document.documentElement.scrollTop = 400;
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));

  const steps = screen.getByRole("list", { name: "계획 생성 단계" });
  expect(document.documentElement.scrollTop).toBe(0);
  expect(document.querySelector(".onboarding-route-boat-position")).toHaveAttribute(
    "transform",
    "translate(40 24)",
  );
  expect(steps).toHaveTextContent("목표 설정");
  expect(steps).toHaveTextContent("예정지출");
  expect(steps).toHaveTextContent("마이데이터");
  expect(steps).toHaveTextContent("계획 생성");
  expect(screen.getByRole("group", { name: "금융 목표" })).toBeInTheDocument();
});

it("shows an illustrated route and consistent Korean money hints without changing direct values", async () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={{ get: vi.fn().mockResolvedValue([]), put: vi.fn(), post: vi.fn() }} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  await userEvent.type(screen.getByLabelText("월 소득"), "3500000");
  await userEvent.type(screen.getByLabelText("월 고정비"), "1200000");
  await userEvent.type(screen.getByLabelText("목표 금액"), "30000000");
  await userEvent.clear(screen.getByLabelText("현재 모은 금액"));
  await userEvent.type(screen.getByLabelText("현재 모은 금액"), "8000000");

  expect(screen.getByRole("img", { name: "계획 생성 항로" })).toBeInTheDocument();
  expect(screen.getByText("3,000만원")).toBeInTheDocument();
  expect(screen.getByText("350만원")).toBeInTheDocument();
  expect(screen.getByText("120만원")).toBeInTheDocument();
  expect(screen.getByText("800만원")).toBeInTheDocument();
  expect(screen.getAllByText("원", { selector: "span" })).toHaveLength(4);
});

it("shows the remaining months for a valid direct goal date", async () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={{ get: vi.fn().mockResolvedValue([]), put: vi.fn(), post: vi.fn() }} />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2028-12-31");

  expect(screen.getByText(/목표까지 \d+개월 남았어요/)).toBeInTheDocument();
});

it("shows an empty demo state without removing direct entry", async () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={{ get: vi.fn().mockResolvedValue([]), put: vi.fn(), post: vi.fn() }} />
    </MemoryRouter>,
  );
  expect(await screen.findByText("지금 선택할 수 있는 데모 항로가 없습니다.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "직접 시작하기" })).toBeInTheDocument();
});

it.each([401, 503])("shows demo loading error %s without removing direct entry", async (status) => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage
        api={{
          get: vi.fn().mockRejectedValue(new ApiError(status, "DEMO_UNAVAILABLE")),
          put: vi.fn(),
          post: vi.fn(),
        }}
      />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("데모 항로를 불러오지 못했습니다");
  expect(screen.getByRole("button", { name: "직접 시작하기" })).toBeInTheDocument();
});

it("seeds the chosen tester then creates its INITIAL plan", async () => {
  const api = {
    get: vi.fn(async (path: string) =>
      path === "/demo/testers" ? demoTesters : { activeGoalId: 41 },
    ),
    put: vi.fn(),
    post: vi.fn(async (path: string) => (path === "/me/demo-seed" ? seedResponse : plan)),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(await screen.findByRole("button", { name: "변동 소비형으로 시작하기" }));

  expect(
    await screen.findByRole("heading", { name: "매달 쓸 수 있는 금액을 선택해보세요" }),
  ).toBeInTheDocument();
  expect(api.post).toHaveBeenCalledWith(
    "/me/demo-seed",
    { testerId: "tester-1" },
    expect.any(Function),
  );
  expect(api.get).toHaveBeenCalledWith("/me", expect.any(Function));
  expect(api.post).toHaveBeenCalledWith(
    "/goals/41/plan-versions",
    { generationType: "INITIAL" },
    expect.any(Function),
  );
  expect(screen.getAllByRole("button", { name: /계획 선택/ })).toHaveLength(3);
});

it("explains each returned plan as a spending and stability choice", async () => {
  const api = {
    get: vi.fn(async (path: string) =>
      path === "/demo/testers" ? demoTesters : { activeGoalId: 41 },
    ),
    put: vi.fn(),
    post: vi.fn(async (path: string) => (path === "/me/demo-seed" ? seedResponse : plan)),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await userEvent.click(await screen.findByRole("button", { name: "변동 소비형으로 시작하기" }));

  expect(await screen.findByText("매달 쓸 수 있는 금액을 선택해보세요")).toBeInTheDocument();
  expect(screen.getAllByText("월 유동지출")).toHaveLength(3);
  expect(screen.getAllByText("계획 안정성")).toHaveLength(3);
  expect(screen.getAllByRole("progressbar", { name: "계획 안정성" })).toHaveLength(3);
  expect(screen.getByText("카드를 선택해 비교해 주세요.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "선택한 계획 확인하기" })).toBeDisabled();
  expect(
    screen.getAllByText("계획 안정성은 과거 소비 변동을 반영한 시뮬레이션 충족률입니다."),
  ).toHaveLength(3);
});

it("keeps demo choices usable after seed failure and blocks repeated clicks", async () => {
  let rejectSeed: (reason: unknown) => void = () => undefined;
  const seed = new Promise((_, reject) => {
    rejectSeed = reject;
  });
  const api = {
    get: vi.fn().mockResolvedValue(demoTesters),
    put: vi.fn(),
    post: vi.fn((path: string) => (path === "/me/demo-seed" ? seed : plan)),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  const button = await screen.findByRole("button", { name: "변동 소비형으로 시작하기" });
  await userEvent.click(button);
  await userEvent.click(button);
  expect(api.post).toHaveBeenCalledTimes(1);

  rejectSeed(new ApiError(503, "DEMO_SEED_FAILED"));
  expect(await screen.findByRole("alert")).toHaveTextContent("입력값은 그대로 보관했습니다");
  expect(screen.getByRole("button", { name: "직접 시작하기" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "변동 소비형으로 시작하기" })).toBeEnabled();
});

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
    await screen.findByRole("heading", { name: "매달 쓸 수 있는 금액을 선택해보세요" }),
  ).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("does not offer the removed sample flow", async () => {
  const api = {
    get: vi.fn().mockResolvedValue([]),
    patch: vi.fn(),
    put: vi.fn(),
    post: vi.fn(),
  };
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <OnboardingPage api={api} />
    </MemoryRouter>,
  );
  await screen.findByText("지금 선택할 수 있는 데모 항로가 없습니다.");
  expect(screen.queryByRole("button", { name: "샘플로 둘러보기" })).not.toBeInTheDocument();
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
    await screen.findByRole("heading", { name: "매달 쓸 수 있는 금액을 선택해보세요" }),
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

it("rejects a plan response missing one of the 70/80/90 options", async () => {
  const incompletePlan: PlanVersion = { ...plan, options: plan.options.slice(0, 2) };
  const api = {
    get: vi.fn(),
    put: vi.fn().mockResolvedValue({}),
    post: vi.fn(async (path: string) =>
      path === "/goals"
        ? { id: 41 }
        : path.includes("plan-versions")
          ? incompletePlan
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

  expect(await screen.findByRole("alert")).toHaveTextContent("70/80/90");
  expect(
    screen.queryByRole("heading", { name: "매달 쓸 수 있는 금액을 선택해보세요" }),
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

it("requires a future goal date", async () => {
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
  expect(api.put).not.toHaveBeenCalled();
});

it("selects any preset option before moving to the dashboard", async () => {
  const api = {
    get: vi.fn(),
    put: vi.fn().mockResolvedValue({}),
    post: vi.fn(async (path: string) =>
      path.includes("select-option")
        ? { ...plan, status: "ACTIVE" }
        : path.includes("plan-versions")
          ? plan
          : path === "/goals"
            ? { id: 41 }
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
  await userEvent.click(await screen.findByRole("button", { name: /80% 계획 선택/ }));

  expect(api.post).not.toHaveBeenCalledWith(
    "/plan-versions/9/select-option",
    expect.anything(),
    expect.anything(),
  );
  await userEvent.click(screen.getByRole("button", { name: "선택한 계획 확인하기" }));
  expect(screen.getByRole("heading", { name: "이 계획으로 시작할까요?" })).toBeInTheDocument();
  expect(screen.getByText("₩1,100,000")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "이 계획으로 시작" }));

  expect(api.post).toHaveBeenCalledWith(
    "/plan-versions/9/select-option",
    { planOptionId: 71 },
    expect.any(Function),
  );
});
