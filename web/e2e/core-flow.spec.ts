import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/auth/refresh", (route) => route.fulfill({
    status: 401,
    contentType: "application/problem+json",
    body: JSON.stringify({ status: 401, code: "TOKEN_EXPIRED" }),
  }));
});

test("missing Google configuration has no demo bypass", async ({ page }) => {
  await page.goto("/login");
  await expect(
    page.getByRole("heading", { name: "오늘의 돈에서 원하는 미래까지." }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("Google 로그인을 준비하지 못했습니다");
  await expect(page.getByText(/데모/)).toHaveCount(0);
});

test("unknown routes show the dedicated 404 screen", async ({ page }) => {
  await page.route("**/api/v1/auth/refresh", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ accessToken: "fixture-access", expiresIn: 900, isNewUser: false }),
  }));
  await page.goto("/missing-route");
  await expect(page.getByRole("heading", { name: "존재하지 않는 화면입니다" })).toBeVisible();
});

test("login layout remains usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 });
  await page.goto("/login");
  await expect(
    page.getByRole("heading", { name: "오늘의 돈에서 원하는 미래까지." }),
  ).toBeInViewport();
});

test("login uses the split layout on compact desktop", async ({ page }) => {
  await page.setViewportSize({ width: 840, height: 900 });
  await page.goto("/login");
  await expect(page.locator(".login-page")).toHaveCSS("grid-template-columns", /\S+\s+\S+/);
  await expect(page.locator(".login-hero")).toBeVisible();
});

test("authenticated dashboard shows the goal path", async ({ page }) => {
  await page.setViewportSize({ width: 840, height: 900 });
  await page.route("**/api/v1/auth/refresh", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ accessToken: "fixture", expiresIn: 900, isNewUser: false }) }));
  await page.route("**/api/v1/dashboard", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
    goal: { id: 1, name: "나만의 작업실", targetAmount: 30000000, currentSavedAmount: 8400000, targetDate: "2028-12-31", remainingMonths: 28 },
    activePlan: { id: 3, status: "ACTIVE", infeasibleReason: null, options: [], explanation: { status: "FALLBACK", text: "계획 수치를 기준으로 선택해 주세요." } },
    selectedOption: { id: 7, optionType: "PRESET", nominalLevel: 0.8, recommendedMonthlySpending: 920000, requiredReductionRate: 0.31, simulationCoverage: 0.83, historicalFeasibilityRatio: 0.58, aggressiveWarning: false, targetCoverageMet: true, percentileBands: [{ monthIndex: 1, p10: 8900000, p25: 9000000, p50: 9100000, p75: 9200000, p90: 9300000 }, { monthIndex: 28, p10: 27000000, p25: 29000000, p50: 31000000, p75: 33000000, p90: 35000000 }] },
    pendingProposal: null, monthProgress: { plannedMonthlySpending: 920000, actualToDate: 510000, paceRatio: 0.92, daysElapsed: 18 },
  }) }));
  await page.goto("/dashboard");
  await expect(page.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeVisible();
  await expect(page.getByText("기본 안내")).toBeVisible();
  await expect(page.locator(".dashboard-grid")).toHaveCSS("grid-template-columns", /\S+\s+\S+/);
  await expect(page.locator(".goal-progress-content")).toHaveCSS("grid-template-columns", /^\S+$/);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(840);
});
