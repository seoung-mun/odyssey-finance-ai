import { expect, test, type APIResponse, type Page } from "@playwright/test";

const token = process.env.E2E_TOKEN;
const retryUsername = process.env.E2E_RETRY_USERNAME;
let authBypassCalls = 0;
const covered = new Set<string>();
const expectedPublicOperations = [
  "refreshAccessToken", "logout", "listDemoTesters", "seedDemoUser", "getCurrentUser",
  "getUserProfile", "upsertUserProfile", "getFinancialProfile", "upsertFinancialProfile",
  "listGoals", "createGoal", "getGoal", "updateGoal", "listScheduledExpenses",
  "createScheduledExpense", "updateScheduledExpense", "listTransactions", "importTransactions",
  "getMonthlySpendingSummary", "getCategorySpendingSummary", "listPlanVersions",
  "createPlanVersion", "getPlanVersion", "getPlanExplanation", "createCustomOption",
  "selectPlanOption", "listReplanEvents", "requestReplan", "decideReplanEvent",
  "retryReplanEvent", "getDashboard", "searchPolicies", "comparePolicyScenario",
];
test.describe.configure({ mode: "serial" });
const responseFor = (page: Page, method: string, path: RegExp) =>
  page.waitForResponse((response) =>
    new RegExp(`^${path.source}$`).test(new URL(response.url()).pathname) &&
    response.request().method() === method,
  );
const success = async (covered: Set<string>, id: string, pending: Promise<APIResponse>, status?: number) => {
  const response = await pending;
  if (status === undefined) expect(response.ok(), id).toBeTruthy();
  else expect(response.status(), id).toBe(status);
  covered.add(id);
  return response;
};
const reportCoverage = (covered: Set<string>, expected: string[]) => {
  expect([...covered].sort()).toEqual([...expected].sort());
  console.info(`PUBLIC_OPERATION_SUCCESS ${covered.size}: ${[...covered].sort().join(",")}`);
};

const authenticate = async (page: Page, username: string) => {
  if (!token) throw new Error("E2E_TOKEN is required; start Core with the e2e profile");
  const response = await page.context().request.post("/api/v1/auth/e2e", {
    params: { username },
    headers: { "X-E2E-Token": token },
  });
  expect(response.status(), "APPROVED_BYPASS /auth/e2e").toBe(200);
  authBypassCalls += 1;
};

const login = async (page: Page, covered: Set<string>, suffix: string) => {
  await test.step("[APPROVED_BYPASS] /auth/e2e (exchangeGoogleToken 아님)", async () => {
    const title = test.info().title.replace(/[^a-zA-Z0-9]+/g, "-").slice(0, 32);
    await authenticate(
      page,
      `public-ui-${title}-${test.info().parallelIndex}-${suffix}-${Date.now()}`,
    );
  });
  await test.step("[refreshAccessToken, listDemoTesters] tester 응답과 렌더", async () => {
    const refresh = responseFor(page, "POST", /\/api\/v1\/auth\/refresh/);
    const testers = responseFor(page, "GET", /\/api\/v1\/demo\/testers/);
    await page.goto("/onboarding");
    await success(covered, "refreshAccessToken", refresh);
    await success(covered, "listDemoTesters", testers);
    await expect(page.getByRole("button", { name: /으로 시작하기$/ }).first()).toBeVisible();
  });
};

const loginAndSeed = async (page: Page, covered: Set<string>) => {
  await login(page, covered, `demo-${test.info().parallelIndex}`);
  await test.step("[seedDemoUser, createPlanVersion] 데모 사용자 선택", async () => {
    const seed = responseFor(page, "POST", /\/api\/v1\/me\/demo-seed/);
    const plan = responseFor(page, "POST", /\/api\/v1\/goals\/\d+\/plan-versions/);
    await page.getByRole("button", { name: /으로 시작하기$/ }).first().click();
    await success(covered, "seedDemoUser", seed);
    await success(covered, "createPlanVersion", plan);
  });
  await test.step("[selectPlanOption] 최초 계획 선택", async () => {
    const selected = responseFor(page, "POST", /\/api\/v1\/plan-versions\/\d+\/select-option/);
    await page.getByRole("button", { name: "80% 계획 선택" }).click();
    await page.getByRole("button", { name: "선택한 계획 확인하기" }).click();
    await page.getByRole("button", { name: "이 계획으로 시작" }).click();
    await success(covered, "selectPlanOption", selected);
  });
};

const searchPoliciesToResults = async (page: Page, covered: Set<string>) => {
  await page.getByRole("button", { name: "주거정책 탐색" }).click();
  await page.getByLabel("지원 목표").selectOption("DORMITORY");
  for (let answered = 0; answered <= 3; answered += 1) {
    const searched = responseFor(page, "POST", /\/api\/v1\/policies\/search/);
    if (answered === 0) await page.getByRole("button", { name: "정책 찾기" }).click();
    else {
      const question = page.locator(".policy-route-step[data-active] select");
      await expect(question).toBeVisible();
      await question.selectOption({ index: 1 });
    }
    const response = await success(covered, "searchPolicies", searched, 200);
    const body = (await response.json()) as { type?: string; results?: unknown[] };
    if (body.type === "RESULTS") {
      expect(body.results?.length).toBeGreaterThan(0);
      expect(body.results?.length).toBeLessThanOrEqual(3);
      await expect(page.locator(".policy-results button").first()).toBeVisible();
      return;
    }
    expect(body.type).toBe("QUESTION");
    expect(answered).toBeLessThan(3);
  }
  throw new Error("POLICY_QUESTION_LIMIT_EXCEEDED");
};

test("Public operation strict REAL subset", async ({ page }) => {
  await loginAndSeed(page, covered);
  await test.step("[transaction reads]", async () => {
    const waits = [
      ["listTransactions", responseFor(page, "GET", /\/api\/v1\/transactions/)],
      ["getMonthlySpendingSummary", responseFor(page, "GET", /\/api\/v1\/transactions\/monthly-summary/)],
      ["getCategorySpendingSummary", responseFor(page, "GET", /\/api\/v1\/transactions\/category-summary/)],
      ["listScheduledExpenses", responseFor(page, "GET", /\/api\/v1\/scheduled-expenses/)],
    ] as const;
    await page.getByRole("link", { name: "거래", exact: true }).click();
    for (const [id, wait] of waits) await success(covered, id, wait);
  });
  await test.step("[importTransactions]", async () => {
    await page.getByLabel("거래 시각").fill("2026-08-21T10:00");
    await page.getByLabel("거래 금액").fill("12000");
    await page.getByLabel("거래 분류").fill("REAL 검증");
    const wait = responseFor(page, "POST", /\/api\/v1\/transactions\/import/);
    await page.getByRole("button", { name: "거래 가져오기" }).click();
    await success(covered, "importTransactions", wait);
  });
  await test.step("[plan reads]", async () => {
    const waits = [
      ["getCurrentUser", responseFor(page, "GET", /\/api\/v1\/me/)],
      ["listGoals", responseFor(page, "GET", /\/api\/v1\/goals/)],
      ["listPlanVersions", responseFor(page, "GET", /\/api\/v1\/goals\/\d+\/plan-versions/)],
      ["getPlanVersion", responseFor(page, "GET", /\/api\/v1\/plan-versions\/\d+/)],
      ["getPlanExplanation", responseFor(page, "GET", /\/api\/v1\/plan-versions\/\d+\/explanation/)],
      ["listReplanEvents", responseFor(page, "GET", /\/api\/v1\/goals\/\d+\/replan-events/)],
    ] as const;
    await page.getByRole("link", { name: "계획", exact: true }).click();
    for (const [id, wait] of waits) await success(covered, id, wait);
  });
  await test.step("[scheduled mutations]", async () => {
    await page.getByRole("link", { name: "거래", exact: true }).click();
    const name = `REAL 예정지출 ${Date.now()}`;
    await page.getByLabel("예정지출 이름").fill(name);
    await page.getByLabel("예정지출 금액").fill("50000");
    await page.getByLabel("예정지출 날짜").fill("2027-12-01");
    const created = responseFor(page, "POST", /\/api\/v1\/scheduled-expenses/);
    await page.getByRole("button", { name: "예정지출 추가" }).click();
    await success(covered, "createScheduledExpense", created, 201);
    await page.getByRole("button", { name: `${name} 수정` }).click();
    await page.getByLabel(`${name} 금액`).fill("55000");
    const updated = responseFor(page, "PATCH", /\/api\/v1\/scheduled-expenses\/\d+/);
    await page.getByRole("button", { name: `${name} 저장` }).click();
    await success(covered, "updateScheduledExpense", updated);
    const cancelled = responseFor(page, "PATCH", /\/api\/v1\/scheduled-expenses\/\d+/);
    await page.getByRole("button", { name: "취소" }).last().click();
    await success(covered, "updateScheduledExpense", cancelled);
  });
  await test.step("[dashboard/profile/financial/goal]", async () => {
    const dashboard = responseFor(page, "GET", /\/api\/v1\/dashboard/);
    await page.getByRole("link", { name: "항로", exact: true }).click();
    await success(covered, "getDashboard", dashboard);
    const items = [
      ["인적 정보 수정", "getUserProfile", "GET", /\/api\/v1\/me\/profile/, "upsertUserProfile", "PUT", /\/api\/v1\/me\/profile/],
      ["재무 정보 수정", "getFinancialProfile", "GET", /\/api\/v1\/me\/financial-profile/, "upsertFinancialProfile", "PUT", /\/api\/v1\/me\/financial-profile/],
      ["목표 수정", "getGoal", "GET", /\/api\/v1\/goals\/\d+/, "updateGoal", "PATCH", /\/api\/v1\/goals\/\d+/],
    ] as const;
    for (const item of items) {
      const loaded = responseFor(page, item[2], item[3]);
      await page.getByRole("button", { name: item[0] }).click();
      await success(covered, item[1], loaded);
      const saved = responseFor(page, item[5], item[6]);
      await page.getByRole("button", { name: "변경사항 저장" }).click();
      await success(covered, item[4], saved);
    }
  });
  await test.step("[logout]", async () => {
    const wait = responseFor(page, "POST", /\/api\/v1\/auth\/logout/);
    await page.getByRole("button", { name: "로그아웃" }).click();
    await success(covered, "logout", wait, 204);
  });
});

test("replan proposal custom option and KEEP are one strict sequence", async ({ page }) => {
  await loginAndSeed(page, covered);
  await page.getByRole("link", { name: "계획", exact: true }).click();
  await expect(page.getByRole("button", { name: "지금 재계획하기" })).toBeVisible();
  const replan = responseFor(page, "POST", /\/api\/v1\/goals\/\d+\/replan/);
  await page.getByRole("button", { name: "지금 재계획하기" }).click();
  await success(covered, "requestReplan", replan, 200);
  await expect(page.getByRole("heading", { name: "새 계획 제안" })).toBeVisible();
  await page.getByLabel("나만의 월 유동지출").fill("5000000");
  const custom = responseFor(page, "POST", /\/api\/v1\/plan-versions\/\d+\/custom-option/);
  await page.getByRole("button", { name: "나만의 소비 한도 만들기" }).click();
  await success(covered, "createCustomOption", custom, 200);
  await expect(page.getByRole("heading", { name: "나만의 소비 한도" })).toBeVisible();
  const decision = responseFor(page, "POST", /\/api\/v1\/replan-events\/\d+\/decision/);
  await page.getByRole("button", { name: "현재 계획 유지" }).click();
  await success(covered, "decideReplanEvent", decision, 200);
});

test("runner-prepared failed replan event retries through the UI", async ({ page }) => {
  if (!retryUsername) {
    throw new Error(
      "E2E_RETRY_USERNAME is required; prepare this user with a failed replan event during a real Analysis outage, then restore Analysis",
    );
  }
  await test.step("[APPROVED_BYPASS] retry prerequisite user", async () => {
    await authenticate(page, retryUsername);
  });
  const refresh = responseFor(page, "POST", /\/api\/v1\/auth\/refresh/);
  await page.goto("/plans");
  await success(covered, "refreshAccessToken", refresh, 200);
  const retry = responseFor(page, "POST", /\/api\/v1\/replan-events\/\d+\/retry/);
  await page.getByRole("button", { name: "재계획 다시 시도" }).click();
  const retryResponse = await success(covered, "retryReplanEvent", retry, 200);
  const retriedPlan = await retryResponse.json() as { id?: unknown; versionNo?: unknown };
  expect(Number.isSafeInteger(retriedPlan.id) && Number(retriedPlan.id) > 0).toBeTruthy();
  expect(Number.isSafeInteger(retriedPlan.versionNo) && Number(retriedPlan.versionNo) > 0).toBeTruthy();
  await expect(page.getByRole("heading", { name: "새 계획 제안" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: `v${String(retriedPlan.versionNo)} 계획 보기` }),
  ).toHaveAttribute("aria-current", "true");
});

test("policy search requires 200 and reaches Top3", async ({ page }) => {
  await loginAndSeed(page, covered);
  await searchPoliciesToResults(page, covered);
  const policies = page.locator(".policy-results .policy-result");
  const compareButton = page.getByRole("button", { name: "정책 반영 가정으로 비교" });
  for (let index = 0; index < await policies.count(); index += 1) {
    await policies.nth(index).click();
    if (await compareButton.isVisible()) break;
  }
  await expect(compareButton).toBeVisible();
  await compareButton.click();
  await page.getByLabel("기관 확정 지원금").fill("100000");
  await page.getByLabel("적용 월").fill("2027-01");
  const endMonth = page.getByLabel("종료 월");
  if (await endMonth.isVisible()) await endMonth.fill("2027-12");
  const scenario = responseFor(page, "POST", /\/api\/v1\/policy-versions\/\d+\/scenario/);
  await page.getByRole("button", { name: "현재 계획과 비교" }).click();
  await success(covered, "comparePolicyScenario", scenario, 200);
  await expect(page.getByRole("status")).toBeVisible();
});

test("fresh onboarding creates a goal through UI", async ({ page }) => {
  await login(page, covered, `fresh-${test.info().parallelIndex}`);
  await page.getByRole("button", { name: "직접 시작하기" }).click();
  await page.getByLabel("월 소득").fill("5000000");
  await page.getByLabel("월 고정비").fill("1500000");
  for (const [index, date] of ["2026-05-10", "2026-06-10", "2026-07-10"].entries()) {
    await page.getByLabel(`거래일 ${index + 1}`).fill(date);
    await page.getByLabel(`거래 금액 ${index + 1}`).fill("500000");
    await page.getByLabel(`거래 분류 ${index + 1}`).fill("생활비");
  }
  await page.getByLabel("목표 이름").fill("REAL 신규 목표");
  await page.getByLabel("목표 금액").fill("20000000");
  await page.getByLabel("현재 모은 금액").fill("1000000");
  await page.getByLabel("목표 날짜").fill("2028-12-01");
  const created = responseFor(page, "POST", /\/api\/v1\/goals/);
  await page.getByRole("button", { name: "계획 만들기" }).click();
  await success(covered, "createGoal", created, 201);
});

test("mobile keyboard navigation and policy dialog focus return", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAndSeed(page, covered);
  const policyButton = page.getByRole("button", { name: "주거정책 탐색" });
  await policyButton.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "주거정책 탐색" })).toBeVisible();
  await expect(page.getByLabel("지원 목표")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(policyButton).toBeFocused();
});

test("successful REAL calls cover every Public operation except the approved auth bypass", () => {
  reportCoverage(covered, expectedPublicOperations);
  console.info(`APPROVED_BYPASS_CALLS ${authBypassCalls}`);
});
