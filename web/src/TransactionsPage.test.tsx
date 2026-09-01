import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "./api";
import { TransactionsPage } from "./TransactionsPage";

const transactionPage = {
  items: [
    {
      id: 1,
      transactionAt: "2026-08-20T10:00:00+09:00",
      amount: 12_000,
      transactionType: "PAYMENT",
      category: "식비",
      merchantName: "식당",
      mcc: null,
      scheduledExpenseId: null,
      sourceId: "MANUAL",
      externalTransactionId: "payment-1",
      refundStatus: "NOT_APPLICABLE",
      resolvedRefundAmount: 0,
      pendingRefundAmount: 0,
      unmatchedRefundAmount: 0,
      refundAllocations: [],
    },
  ],
  nextCursor: "cursor-2",
};

const monthly = [
  {
    yearMonth: "2026-08-01",
    totalVariableSpending: 12_000,
    grossPaymentSpending: 12_000,
    linkedRefundAmount: 0,
    unmatchedRefundInflow: 0,
    adjustedConsumption: 12_000,
    netCashFlow: -12_000,
    bootstrapEligibleSpending: 12_000,
  },
];

const category = {
  months: 3,
  currentAvgVariableSpending: 15_000,
  categories: [{ category: "식비", monthlyAverage: 15_000 }],
};

const scheduled = [
  {
    id: 4,
    name: "월세",
    amount: 500_000,
    scheduledDate: "2026-09-01",
    status: "PLANNED",
    matchedTransactionCount: 0,
    matchedAmount: 0,
    triggeredReplanEventId: null,
  },
];

const api = () => ({
  get: vi.fn((path: string) => {
    if (path.startsWith("/transactions?")) return Promise.resolve(transactionPage);
    if (path === "/transactions/monthly-summary?months=6") return Promise.resolve(monthly);
    if (path === "/transactions/category-summary?months=3") return Promise.resolve(category);
    if (path === "/scheduled-expenses?status=PLANNED") return Promise.resolve(scheduled);
    return Promise.resolve([]);
  }),
  post: vi.fn().mockResolvedValue({ inserted: 1, skipped: 0, allocationsInserted: 0, allocationsPending: 0 }),
  patch: vi.fn().mockResolvedValue(scheduled[0]),
});

const renderPage = (client = api()) => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <TransactionsPage api={client} />
    </MemoryRouter>,
  );
  return client;
};

it("loads the ledger, monthly dashboard, category average, and planned expenses", async () => {
  const client = renderPage();

  expect(await screen.findByRole("heading", { name: "거래 원장" })).toBeInTheDocument();
  expect(screen.getByText("식당")).toBeInTheDocument();
  expect(screen.getByText("₩12,000")).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "월별 소비 요약" })).toHaveTextContent("₩12,000");
  expect(screen.getByRole("region", { name: "카테고리별 월평균" })).toHaveTextContent("₩15,000");
  expect(screen.getByRole("region", { name: "예정지출" })).toHaveTextContent("월세");
  expect(client.get).toHaveBeenCalledTimes(4);
});

it("uses the selected category and next cursor when the user filters and loads another page", async () => {
  const client = renderPage();
  await screen.findByText("식당");

  await userEvent.type(screen.getByLabelText("카테고리 필터"), "식비");
  await userEvent.click(screen.getByRole("button", { name: "필터 적용" }));
  await waitFor(() =>
    expect(client.get).toHaveBeenCalledWith(
      "/transactions?category=%EC%8B%9D%EB%B9%84&limit=50",
      expect.any(Function),
    ),
  );
  await userEvent.click(screen.getByRole("button", { name: "다음 거래 불러오기" }));
  await waitFor(() =>
    expect(client.get).toHaveBeenCalledWith(
      "/transactions?category=%EC%8B%9D%EB%B9%84&cursor=cursor-2&limit=50",
      expect.any(Function),
    ),
  );
});

it("sends from, to, and category while applying transaction type in the ledger", async () => {
  const client = renderPage();
  await screen.findByText("식당");
  await userEvent.type(screen.getByLabelText("조회 시작일"), "2026-08-01");
  await userEvent.type(screen.getByLabelText("조회 종료일"), "2026-09-01");
  await userEvent.selectOptions(screen.getByLabelText("조회 거래 유형"), "REFUND");
  await userEvent.type(screen.getByLabelText("카테고리 필터"), "식비");
  await userEvent.click(screen.getByRole("button", { name: "필터 적용" }));
  await waitFor(() =>
    expect(client.get).toHaveBeenCalledWith(
      "/transactions?from=2026-08-01T00%3A00%3A00%2B09%3A00&to=2026-09-01T23%3A59%3A59%2B09%3A00&category=%EC%8B%9D%EB%B9%84&limit=50",
      expect.any(Function),
    ),
  );
  expect(screen.queryByText("식당")).not.toBeInTheDocument();
});

it("blocks an invalid refund allocation before it is imported", async () => {
  const client = renderPage();
  await screen.findByText("식당");

  await userEvent.selectOptions(screen.getByLabelText("거래 유형"), "REFUND");
  await userEvent.type(screen.getByLabelText("거래 시각"), "2026-08-21T10:00");
  await userEvent.type(screen.getByLabelText("거래 금액"), "10000");
  await userEvent.type(screen.getByLabelText("거래 분류"), "식비");
  await userEvent.type(screen.getByLabelText("결제 source ID"), "MANUAL");
  await userEvent.type(screen.getByLabelText("결제 외부 거래 ID"), "payment-1");
  await userEvent.type(screen.getByLabelText("환불 연결 금액"), "10001");
  await userEvent.click(screen.getByRole("button", { name: "거래 가져오기" }));

  expect(screen.getByRole("alert")).toHaveTextContent("환불 연결 금액은 환불 금액 이하여야 합니다");
  expect(client.post).not.toHaveBeenCalled();
});

it("sends a manual refund and its allocation through the import contract", async () => {
  const client = renderPage();
  await screen.findByText("식당");

  await userEvent.type(screen.getByLabelText("거래 시각"), "2026-08-21T10:00");
  await userEvent.type(screen.getByLabelText("거래 금액"), "10000");
  await userEvent.type(screen.getByLabelText("거래 분류"), "식비");
  await userEvent.selectOptions(screen.getByLabelText("거래 유형"), "REFUND");
  await userEvent.type(screen.getByLabelText("결제 source ID"), "MANUAL");
  await userEvent.type(screen.getByLabelText("결제 외부 거래 ID"), "payment-1");
  await userEvent.type(screen.getByLabelText("환불 연결 금액"), "10000");
  await userEvent.click(screen.getByRole("button", { name: "거래 가져오기" }));

  await waitFor(() =>
    expect(client.post).toHaveBeenCalledWith(
      "/transactions/import",
      expect.objectContaining({
        transactions: [
          expect.objectContaining({
            transactionType: "REFUND",
            sourceId: "MANUAL",
            refundAllocations: [
              { paymentSourceId: "MANUAL", paymentExternalTransactionId: "payment-1", amount: 10_000 },
            ],
          }),
        ],
      }),
      expect.any(Function),
    ),
  );
});

it("keeps the planned expense form visible after a 422 response", async () => {
  const client = api();
  client.post.mockRejectedValueOnce(new ApiError(422, "INVALID_SCHEDULE"));
  renderPage(client);
  await screen.findByText("식당");

  await userEvent.type(screen.getByLabelText("예정지출 이름"), "보증금");
  await userEvent.type(screen.getByLabelText("예정지출 금액"), "1000000");
  await userEvent.type(screen.getByLabelText("예정지출 날짜"), "2026-10-01");
  await userEvent.click(screen.getByRole("button", { name: "예정지출 추가" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("입력값을 확인해 주세요");
  expect(screen.getByLabelText("예정지출 이름")).toHaveValue("보증금");
});

it("updates a planned expense before cancelling it", async () => {
  const client = renderPage();
  await screen.findByRole("button", { name: "취소" });

  await userEvent.click(screen.getByRole("button", { name: "월세 수정" }));
  await userEvent.clear(screen.getByLabelText("월세 금액"));
  await userEvent.type(screen.getByLabelText("월세 금액"), "550000");
  await userEvent.click(screen.getByRole("button", { name: "월세 저장" }));

  await waitFor(() =>
    expect(client.patch).toHaveBeenCalledWith(
      "/scheduled-expenses/4",
      { name: "월세", amount: 550_000, scheduledDate: "2026-09-01" },
      expect.any(Function),
    ),
  );
});

it("keeps healthy regions usable and retries only a failed monthly summary", async () => {
  const client = api();
  client.get.mockImplementationOnce((path: string) => {
    if (path.startsWith("/transactions?")) return Promise.resolve(transactionPage);
    throw new Error(`unexpected first GET ${path}`);
  });
  const original = client.get.getMockImplementation();
  let monthlyAttempts = 0;
  client.get.mockImplementation((path: string) => {
    if (path === "/transactions/monthly-summary?months=6" && monthlyAttempts++ === 0)
      return Promise.reject(new ApiError(503, "SUMMARY_UNAVAILABLE"));
    if (path.startsWith("/transactions?")) return Promise.resolve(transactionPage);
    if (path === "/transactions/monthly-summary?months=6") return Promise.resolve(monthly);
    if (path === "/transactions/category-summary?months=3") return Promise.resolve(category);
    if (path === "/scheduled-expenses?status=PLANNED") return Promise.resolve(scheduled);
    return original?.(path);
  });
  renderPage(client);

  expect(await screen.findByText("식당")).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "예정지출" })).toHaveTextContent("월세");
  const monthlyRegion = screen.getByRole("region", { name: "월별 소비 요약" });
  expect(monthlyRegion).toHaveTextContent("서비스가 준비 중입니다");
  await userEvent.click(screen.getByRole("button", { name: "월별 소비 다시 불러오기" }));
  expect(await screen.findByText("2026-08-01: ₩12,000")).toBeInTheDocument();
  expect(client.get).toHaveBeenCalledTimes(5);
});
