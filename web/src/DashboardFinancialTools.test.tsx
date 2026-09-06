import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DashboardDialogs } from "./DashboardDialogs";

const policy = {
  policyVersionId: 44,
  title: "청년 주거비 지원",
  summary: "월 주거비를 지원합니다.",
  planConnection: "월 고정비 부담을 낮출 수 있습니다.",
  supportDetails: "월 최대 20만원",
  confirmedConditions: ["청년"],
  additionalChecks: ["기관 심사"],
  applicationPeriod: "2026년 상시",
  asOfDate: "2026-09-01",
  eligibilityStatus: "NEEDS_CONFIRMATION",
  calculationMode: "ONE_TIME_FUNDING",
  source: {
    organization: "서울시",
    officialUrl: "https://example.go.kr/policy",
    sourceVersion: "2026-09",
    lastVerifiedAt: "2026-09-01T10:00:00+09:00",
    locators: ["공고문 3쪽"],
  },
};

const savings = {
  planAsOfDate: "2026-09-01",
  remainingMonths: 18,
  monthlySavings: 500_000,
  recommendations: [{
    productId: 11,
    optionId: 21,
    finCoNo: "001",
    finPrdtCd: "SAVE-1",
    bankName: "오디세이은행",
    productName: "목표 적금",
    reserveType: "정액적립식",
    termMonths: 12,
    baseRate: 3.2,
    maximumRate: 3.5,
    monthlySavings: 500_000,
    pretaxInterest: 104_000,
    acceleratedMonths: 1,
    availableConditions: [{ conditionId: 31, label: "급여이체", bonusRate: 0.3 }],
  }],
};

it("opens the current plan overview without sending a chat message", async () => {
  const api = { get: vi.fn(), post: vi.fn() };
  render(<DashboardDialogs
    api={api}
    goalId={3}
    currentPlanVersionId={8}
    assistantContext={{
      goalName: "내집마련",
      currentSavedAmount: 10_000_000,
      targetAmount: 100_000_000,
      targetDate: "2030-12-31",
      monthlySpending: 900_000,
      stability: 0.82,
    }}
  />);

  await userEvent.click(screen.getByRole("button", { name: "내 계획 점검해줘" }));
  expect(screen.getByRole("region", { name: "현재 계획 점검" })).toHaveTextContent("내집마련");
  expect(screen.getByRole("region", { name: "현재 계획 점검" })).toHaveTextContent("82%");
  expect(screen.queryByRole("button", { name: "AI 도우미 열기" })).not.toBeInTheDocument();
  expect(within(screen.getByLabelText("빠른 질문")).getAllByRole("button")).toHaveLength(3);
  expect(screen.queryByRole("button", { name: "내 계획 상태 알려줘" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "재계획이 필요한지 알려줘" })).not.toBeInTheDocument();
  expect(api.post).not.toHaveBeenCalled();
});

it("shows ACTIVE-plan savings recommendations and calculates a condition what-if", async () => {
  const api = {
    get: vi.fn(async (path: string) => path === "/savings/recommendations" ? savings : []),
    post: vi.fn().mockResolvedValue({
      calculable: true,
      message: null,
      productId: 11,
      optionId: 21,
      termMonths: 12,
      appliedRate: 3.5,
      monthlySavings: 500_000,
      pretaxInterest: 114_000,
      acceleratedMonths: 1,
      appliedConditions: [{ conditionId: 31, label: "급여이체", bonusRate: 0.3 }],
    }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);

  await userEvent.click(screen.getByRole("button", { name: /현재 계획에 맞는 적금 추천/ }));
  const dialog = await screen.findByRole("dialog", { name: "적금 상품 추천" });
  expect(within(dialog).getByText("50만원")).toBeInTheDocument();
  expect(within(dialog).getByText("목표 적금")).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", { name: "우대조건 확인" }));
  await userEvent.click(within(dialog).getByRole("checkbox", { name: /급여이체/ }));
  await userEvent.click(within(dialog).getByRole("button", { name: "선택 조건으로 비교" }));

  expect(await within(dialog).findByText("3.2% → 3.5%")).toBeInTheDocument();
  expect(api.post).toHaveBeenCalledWith(
    "/savings/products/11/what-if",
    { optionId: 21, conditionIds: [31] },
    expect.any(Function),
  );
});

it("renders chat savings Top 3 without making a second recommendation GET", async () => {
  const api = {
    get: vi.fn(),
    post: vi.fn().mockResolvedValue({
      sessionId: "34e3c490-7a61-4ea4-a223-302cda850381",
      intent: "SAVINGS_RECOMMENDATION",
      sessionMode: "STATEFUL",
      message: "현재 계획에 맞는 적금 추천입니다.",
      savingsRecommendations: savings,
    }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);

  await userEvent.click(screen.getByRole("button", { name: "AI 도우미 열기" }));
  await userEvent.click(screen.getByRole("button", { name: "내 계획에 맞는 적금 추천해줘" }));
  expect(await screen.findByText("오디세이은행 · 목표 적금")).toBeInTheDocument();
  expect(api.get).not.toHaveBeenCalledWith("/savings/recommendations", expect.any(Function));
});

it("opens the existing policy dialog for the POLICY_SEARCH chat intent", async () => {
  const api = {
    get: vi.fn().mockResolvedValue([]),
    post: vi.fn().mockResolvedValue({
      sessionId: "34e3c490-7a61-4ea4-a223-302cda850381",
      intent: "POLICY_SEARCH",
      sessionMode: "STATELESS_FALLBACK",
      message: "정책 탐색을 시작합니다.",
      savingsRecommendations: null,
    }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);

  await userEvent.click(screen.getByRole("button", { name: "AI 도우미 열기" }));
  await userEvent.click(screen.getByRole("button", { name: "내게 맞는 주거정책 찾아줘" }));
  expect(await screen.findByRole("dialog", { name: "주거정책 탐색" })).toBeVisible();
  expect(screen.queryByRole("dialog", { name: "AI 도우미" })).not.toBeInTheDocument();
});

it("confirms an institutional policy benefit and automatically hands off to replan", async () => {
  const benefit = {
    id: 91,
    goalId: 3,
    policyVersionId: 44,
    adjustmentType: "ONE_TIME_FUNDING",
    amountWon: 1_000_000,
    startYearMonth: "2026-10",
    endYearMonth: null,
    status: "CONFIRMED",
    confirmedAt: "2026-09-01T10:00:00+09:00",
  };
  const api = {
    get: vi.fn().mockResolvedValue([]),
    post: vi.fn(async (path: string) => path === "/policies/search"
      ? { type: "RESULTS", results: [policy] }
      : benefit),
  };
  const onRequestReplan = vi.fn().mockResolvedValue(true);
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} onRequestReplan={onRequestReplan} />);

  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  await userEvent.click(await screen.findByRole("button", { name: /청년 주거비 지원/ }));
  await userEvent.click(screen.getByRole("button", { name: "실제 지원이 확정됐어요" }));
  await userEvent.click(screen.getByRole("checkbox", { name: /실제 지원이 확정/ }));
  await userEvent.type(screen.getByLabelText("지원 금액"), "1000000");
  await userEvent.type(screen.getByLabelText("시작 월"), "2026-10");
  await userEvent.click(screen.getByRole("button", { name: "확정 지원 저장" }));

  await waitFor(() => expect(onRequestReplan).toHaveBeenCalledTimes(1));
  expect(onRequestReplan).toHaveBeenCalledWith(false);
  expect(api.post).toHaveBeenCalledWith(
    "/policy-versions/44/benefits",
    { goalId: 3, institutionConfirmed: true, amountWon: 1_000_000, startYearMonth: "2026-10" },
    expect.any(Function),
  );
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "주거정책 탐색" })).not.toBeInTheDocument());
});

it("keeps a saved benefit distinct when the automatic replan fails", async () => {
  const benefit = {
    id: 91,
    goalId: 3,
    policyVersionId: 44,
    adjustmentType: "ONE_TIME_FUNDING",
    amountWon: 1_000_000,
    startYearMonth: "2026-10",
    endYearMonth: null,
    status: "CONFIRMED",
    confirmedAt: "2026-09-01T10:00:00+09:00",
  };
  const api = {
    get: vi.fn().mockResolvedValue([]),
    post: vi.fn(async (path: string) => path === "/policies/search"
      ? { type: "RESULTS", results: [policy] }
      : benefit),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} onRequestReplan={vi.fn().mockResolvedValue(false)} />);

  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  await userEvent.click(await screen.findByRole("button", { name: /청년 주거비 지원/ }));
  await userEvent.click(screen.getByRole("button", { name: "실제 지원이 확정됐어요" }));
  await userEvent.click(screen.getByRole("checkbox", { name: /실제 지원이 확정/ }));
  await userEvent.type(screen.getByLabelText("지원 금액"), "1000000");
  await userEvent.type(screen.getByLabelText("시작 월"), "2026-10");
  await userEvent.click(screen.getByRole("button", { name: "확정 지원 저장" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("지원 내용은 저장되었지만 재계획을 시작하지 못했습니다.");
});
