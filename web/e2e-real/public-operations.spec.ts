import { expect, test, type Page } from "@playwright/test";

const token = process.env.E2E_TOKEN;

const responseFor = (page: Page, method: string, pattern: RegExp) =>
  page.waitForResponse(
    (response) => pattern.test(new URL(response.url()).pathname) && response.request().method() === method,
  );

const loginAndSeed = async (page: Page) => {
  if (!token) throw new Error("E2E_TOKEN is required; start Core with the e2e profile");
  await test.step("[exchangeGoogleToken] Google exchange 하나만 e2e 인증으로 우회", async () => {
    await page.goto("/login");
    const status = await page.evaluate(
      async ({ username, e2eToken }) =>
        (
          await fetch(`/api/v1/auth/e2e?username=${encodeURIComponent(username)}`, {
            method: "POST",
            credentials: "include",
            headers: { "X-E2E-Token": e2eToken },
          })
        ).status,
      {
        username: `public-ui-${Date.now()}-${test.info().parallelIndex}`,
        e2eToken: token,
      },
    );
    expect(status).toBe(200);
  });
  await test.step("[refreshAccessToken] 보호 화면 진입", async () => {
    const response = responseFor(page, "POST", /\/api\/v1\/auth\/refresh$/);
    await page.goto("/onboarding");
    expect((await response).ok()).toBeTruthy();
  });
  await test.step("[listDemoTesters, seedDemoUser, createPlanVersion] 데모 사용자 선택", async () => {
    await expect(page.getByRole("button", { name: /으로 시작하기$/ }).first()).toBeVisible();
    const seeded = responseFor(page, "POST", /\/api\/v1\/me\/demo-seed$/);
    const planned = responseFor(page, "POST", /\/api\/v1\/goals\/\d+\/plan-versions$/);
    await page.getByRole("button", { name: /으로 시작하기$/ }).first().click();
    expect((await seeded).ok()).toBeTruthy();
    expect((await planned).ok()).toBeTruthy();
  });
  await test.step("[selectPlanOption] 최초 계획 선택", async () => {
    const selected = responseFor(page, "POST", /\/api\/v1\/plan-versions\/\d+\/select-option$/);
    await page.getByRole("button", { name: "80% 계획 선택" }).click();
    await page.getByRole("button", { name: "선택한 계획 확인하기" }).click();
    await page.getByRole("button", { name: "이 계획으로 시작" }).click();
    expect((await selected).ok()).toBeTruthy();
  });
};

test("현재 구현된 Public operation REAL subset", async ({ page }) => {
  await loginAndSeed(page);

  await test.step("[listTransactions, getMonthlySpendingSummary, getCategorySpendingSummary, listScheduledExpenses] 거래 원장", async () => {
    const responses = [
      responseFor(page, "GET", /\/api\/v1\/transactions$/),
      responseFor(page, "GET", /\/api\/v1\/transactions\/monthly-summary$/),
      responseFor(page, "GET", /\/api\/v1\/transactions\/category-summary$/),
      responseFor(page, "GET", /\/api\/v1\/scheduled-expenses$/),
    ];
    await page.getByRole("link", { name: "거래", exact: true }).click();
    for (const response of await Promise.all(responses)) expect(response.ok()).toBeTruthy();
    await expect(page.getByRole("heading", { name: "거래 원장" })).toBeVisible();
  });

  await test.step("[importTransactions] 화면에서 PAYMENT 가져오기", async () => {
    await page.getByLabel("거래 시각").fill("2026-08-21T10:00");
    await page.getByLabel("거래 금액").fill("12000");
    await page.getByLabel("거래 분류").fill("REAL 검증");
    const imported = responseFor(page, "POST", /\/api\/v1\/transactions\/import$/);
    await page.getByRole("button", { name: "거래 가져오기" }).click();
    expect((await imported).ok()).toBeTruthy();
  });

  await test.step("[getCurrentUser, listGoals, listPlanVersions, getPlanVersion, getPlanExplanation, listReplanEvents] 계획 항로", async () => {
    const responses = [
      responseFor(page, "GET", /\/api\/v1\/me$/),
      responseFor(page, "GET", /\/api\/v1\/goals$/),
      responseFor(page, "GET", /\/api\/v1\/goals\/\d+\/plan-versions$/),
      responseFor(page, "GET", /\/api\/v1\/plan-versions\/\d+$/),
      responseFor(page, "GET", /\/api\/v1\/plan-versions\/\d+\/explanation$/),
      responseFor(page, "GET", /\/api\/v1\/goals\/\d+\/replan-events$/),
    ];
    await page.getByRole("link", { name: "계획", exact: true }).click();
    for (const response of await Promise.all(responses)) expect(response.ok()).toBeTruthy();
    expect(await page.locator("body").innerText()).toContain("계획");
  });

  await test.step("[createScheduledExpense, updateScheduledExpense] 예정지출 생성·수정·취소", async () => {
    await page.getByRole("link", { name: "거래", exact: true }).click();
    const name = `REAL 예정지출 ${Date.now()}`;
    await page.getByLabel("예정지출 이름").fill(name);
    await page.getByLabel("예정지출 금액").fill("50000");
    await page.getByLabel("예정지출 날짜").fill("2027-12-01");
    const created = responseFor(page, "POST", /\/api\/v1\/scheduled-expenses$/);
    await page.getByRole("button", { name: "예정지출 추가" }).click();
    expect((await created).ok()).toBeTruthy();
    await page.getByRole("button", { name: `${name} 수정` }).click();
    await page.getByLabel(`${name} 금액`).fill("55000");
    const updated = responseFor(page, "PATCH", /\/api\/v1\/scheduled-expenses\/\d+$/);
    await page.getByRole("button", { name: `${name} 저장` }).click();
    expect((await updated).ok()).toBeTruthy();
    const cancelled = responseFor(page, "PATCH", /\/api\/v1\/scheduled-expenses\/\d+$/);
    await page.getByRole("button", { name: "취소" }).last().click();
    expect((await cancelled).ok()).toBeTruthy();
  });

  await test.step("[getDashboard, getUserProfile] Dashboard 프로필 dialog", async () => {
    const dashboard = responseFor(page, "GET", /\/api\/v1\/dashboard$/);
    await page.getByRole("link", { name: "항로", exact: true }).click();
    expect((await dashboard).ok()).toBeTruthy();
    const profile = responseFor(page, "GET", /\/api\/v1\/me\/profile$/);
    await page.getByRole("button", { name: "인적 정보 수정" }).click();
    expect((await profile).ok()).toBeTruthy();
    const saved = responseFor(page, "PUT", /\/api\/v1\/me\/profile$/);
    await page.getByRole("button", { name: "변경사항 저장" }).click();
    expect((await saved).ok()).toBeTruthy();
  });

  await test.step("[getFinancialProfile, upsertFinancialProfile] 재무 정보 동일값 저장", async () => {
    const loaded = responseFor(page, "GET", /\/api\/v1\/me\/financial-profile$/);
    await page.getByRole("button", { name: "재무 정보 수정" }).click();
    expect((await loaded).ok()).toBeTruthy();
    const saved = responseFor(page, "PUT", /\/api\/v1\/me\/financial-profile$/);
    await page.getByRole("button", { name: "변경사항 저장" }).click();
    expect((await saved).ok()).toBeTruthy();
  });

  await test.step("[getGoal, updateGoal] 목표 동일값 저장", async () => {
    const loaded = responseFor(page, "GET", /\/api\/v1\/goals\/\d+$/);
    await page.getByRole("button", { name: "목표 수정" }).click();
    expect((await loaded).ok()).toBeTruthy();
    const saved = responseFor(page, "PATCH", /\/api\/v1\/goals\/\d+$/);
    await page.getByRole("button", { name: "변경사항 저장" }).click();
    expect((await saved).ok()).toBeTruthy();
  });

  await test.step("[searchPolicies] 정책 찾기", async () => {
    await page.getByRole("button", { name: "주거정책 탐색" }).click();
    const searched = responseFor(page, "POST", /\/api\/v1\/policies\/search$/);
    await page.getByRole("button", { name: "정책 찾기" }).click();
    expect([200, 503]).toContain((await searched).status());
    await page.getByRole("button", { name: "닫기" }).click();
  });

  await test.step("[logout] 공통 항해 nav", async () => {
    const logout = responseFor(page, "POST", /\/api\/v1\/auth\/logout$/);
    await page.getByRole("button", { name: "로그아웃" }).click();
    expect((await logout).status()).toBe(204);
    await expect(page).toHaveURL(/\/login$/);
  });
});

test("mobile keyboard navigation and policy dialog focus return", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAndSeed(page);
  const policyButton = page.getByRole("button", { name: "주거정책 탐색" });
  await policyButton.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "주거정책 탐색" })).toBeVisible();
  await expect(page.getByLabel("지원 목표")).toBeFocused();
  await page.screenshot({ path: "e2e-artifacts/task-6-mobile-policy.png", fullPage: true });
  await page.keyboard.press("Escape");
  await expect(policyButton).toBeFocused();
});
