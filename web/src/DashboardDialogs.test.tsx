import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DashboardDialogs } from "./DashboardDialogs";
import { ApiError } from "./api";

const result = {
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

it("follows the policy route and discards confirmed values when the dialog closes", async () => {
  const api = {
    get: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    post: vi
      .fn()
      .mockResolvedValueOnce({ type: "RESULTS", results: [result] })
      .mockResolvedValueOnce({
        currentPlanSummary: {
          recommendedMonthlySpending: 900_000,
          requiredReductionRate: 0.2,
          simulationCoverage: 0.8,
        },
        assumedPlanSummary: {
          recommendedMonthlySpending: 950_000,
          requiredReductionRate: 0.15,
          simulationCoverage: 0.85,
        },
        adjustment: {
          type: "ONE_TIME_FUNDING",
          amountWon: 1_000_000,
          startYearMonth: "2026-10",
          sourceVersion: "2026-09",
        },
        assumptionNotice: "현재 계획에는 반영되지 않습니다.",
        source: result.source,
      }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);

  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  expect(screen.getByRole("dialog", { name: "주거정책 탐색" })).toBeVisible();
  await userEvent.selectOptions(screen.getByLabelText("지원 목표"), "MONTHLY_RENT");
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  await userEvent.click(await screen.findByRole("button", { name: /청년 주거비 지원/ }));
  expect(screen.getByRole("link", { name: "서울시 공식 원문" })).toHaveAttribute(
    "href",
    "https://example.go.kr/policy",
  );
  await userEvent.click(screen.getByRole("button", { name: "내 계획에 미리 적용해보기" }));
  await userEvent.type(screen.getByLabelText("예상 지원 금액"), "1000000");
  await userEvent.type(screen.getByLabelText("적용 시작 월"), "2026-10");
  await userEvent.click(screen.getByRole("button", { name: "현재 계획과 비교" }));
  expect(await screen.findByText("95만원")).toBeInTheDocument();
  expect(screen.getByText("현재 계획에는 반영되지 않습니다.")).toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", { name: "닫기" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  expect(screen.queryByDisplayValue("1000000")).not.toBeInTheDocument();
  expect(screen.queryByText("95만원")).not.toBeInTheDocument();
});

it("keeps informational policies free of scenario controls", async () => {
  const api = {
    get: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    post: vi.fn().mockResolvedValue({
      type: "RESULTS",
      results: [{ ...result, calculationMode: "INFORMATIONAL" }],
    }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);
  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  await userEvent.click(await screen.findByRole("button", { name: /청년 주거비 지원/ }));
  expect(
    screen.queryByRole("button", { name: "내 계획에 미리 적용해보기" }),
  ).not.toBeInTheDocument();
});

it("clears an expired policy and returns to a fresh search", async () => {
  const api = {
    get: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    post: vi
      .fn()
      .mockResolvedValueOnce({ type: "RESULTS", results: [result] })
      .mockRejectedValueOnce(new ApiError(409, "POLICY_EXPIRED"))
      .mockResolvedValueOnce({ type: "RESULTS", results: [] }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);
  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  await userEvent.click(await screen.findByRole("button", { name: /청년 주거비 지원/ }));
  await userEvent.click(screen.getByRole("button", { name: "내 계획에 미리 적용해보기" }));
  await userEvent.type(screen.getByLabelText("예상 지원 금액"), "1000000");
  await userEvent.type(screen.getByLabelText("적용 시작 월"), "2026-10");
  await userEvent.click(screen.getByRole("button", { name: "현재 계획과 비교" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("정책을 다시 검색");
  expect(screen.queryByRole("button", { name: "현재 계획과 비교" })).not.toBeInTheDocument();
  expect(screen.queryByText("청년 주거비 지원")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  expect(api.post).toHaveBeenLastCalledWith(
    "/policies/search",
    { supportGoal: "MONTHLY_RENT", answers: [] },
    expect.any(Function),
  );
});

it("rejects a fourth policy question and restarts without stale answers", async () => {
  const question = (index: number) => ({
    type: "QUESTION" as const,
    question: {
      questionId: `question-${index}`,
      label: `추가 질문 ${index}`,
      options: [{ value: `answer-${index}`, label: `답변 ${index}` }],
    },
  });
  const api = {
    get: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    post: vi
      .fn()
      .mockResolvedValueOnce(question(1))
      .mockResolvedValueOnce(question(2))
      .mockResolvedValueOnce(question(3))
      .mockResolvedValueOnce(question(4))
      .mockResolvedValueOnce({ type: "RESULTS", results: [] }),
  };
  render(<DashboardDialogs api={api} goalId={3} currentPlanVersionId={8} />);
  await userEvent.click(screen.getByRole("button", { name: /주거정책 찾기/ }));
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  for (let index = 1; index <= 3; index += 1) {
    await userEvent.selectOptions(
      await screen.findByLabelText(`추가 질문 ${index}`),
      `answer-${index}`,
    );
  }

  expect(await screen.findByRole("alert")).toHaveTextContent("처음부터 다시 검색");
  expect(screen.queryByLabelText("추가 질문 4")).not.toBeInTheDocument();
  expect(screen.queryByText("추가 확인 4/3")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "정책 찾기" }));
  expect(api.post).toHaveBeenLastCalledWith(
    "/policies/search",
    { supportGoal: "MONTHLY_RENT", answers: [] },
    expect.any(Function),
  );
});
