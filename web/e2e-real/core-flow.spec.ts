import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const e2eToken = process.env.E2E_TOKEN;
const baseURL = process.env.E2E_BASE_URL!;

const browserFetch = async (
  page: Page,
  path: string,
  init: { method?: string; token?: string; body?: unknown } = {},
) =>
  page.evaluate(
    async ({ path, init }) => {
      const response = await fetch(path, {
        method: init.method,
        credentials: "include",
        headers: {
          ...(init.token ? { Authorization: `Bearer ${init.token}` } : {}),
          ...(init.body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
      });
      return {
        status: response.status,
        body: response.headers.get("content-type")?.includes("json")
          ? await response.json()
          : null,
      };
    },
    { path, init },
  );

const login = async (page: Page, context: BrowserContext, username: string) => {
  if (!e2eToken) throw new Error("E2E_TOKEN is required; start Core with the e2e profile");
  await page.goto("/login");
  const auth = await page.evaluate(
    async ({ username, token }) => {
      const response = await fetch(`/api/v1/auth/e2e?username=${encodeURIComponent(username)}`, {
        method: "POST",
        credentials: "include",
        headers: { "X-E2E-Token": token },
      });
      return { status: response.status, body: await response.json() };
    },
    { username, token: e2eToken },
  );
  expect(auth.status).toBe(200);
  const refresh = (await context.cookies()).find((cookie) => cookie.name === "refresh_token");
  expect(refresh).toMatchObject({
    secure: true,
    httpOnly: true,
    sameSite: "Strict",
    path: "/api/v1/auth",
  });
  return (auth.body as { accessToken: string }).accessToken;
};

const denied = async (response: Promise<{ status: number }>) => {
  expect((await response).status).toBe(404);
};

const futureDate = (months: number) => {
  const date = new Date();
  date.setUTCMonth(date.getUTCMonth() + months);
  return date.toISOString().slice(0, 10);
};

const completedMonthDate = (monthsAgo = 2) => {
  const date = new Date();
  date.setUTCMonth(date.getUTCMonth() - monthsAgo, 10);
  return `${date.toISOString().slice(0, 10)}T12:00:00+09:00`;
};

test("real HTTPS Core: demo tester UI seeds, creates, selects, and restores a plan", async ({
  context,
  page,
}) => {
  await login(page, context, `web-demo-${Date.now()}-${test.info().parallelIndex}`);

  const testersResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/demo/testers") && response.request().method() === "GET",
  );
  await page.goto("/onboarding");
  const testers = await testersResponse;
  expect(testers.ok()).toBeTruthy();
  expect(testers.request().headers().authorization).toMatch(/^Bearer /);
  expect(await testers.json()).toHaveLength(3);

  const testerButtons = page.getByRole("button", { name: /으로 시작하기$/ });
  await expect(testerButtons).toHaveCount(3);

  const seededResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/me/demo-seed") && response.request().method() === "POST",
  );
  const planResponse = page.waitForResponse(
    (response) =>
      /\/api\/v1\/goals\/\d+\/plan-versions$/.test(response.url()) &&
      response.request().method() === "POST",
  );
  await testerButtons.first().click();
  const seeded = await seededResponse;
  expect(seeded.ok()).toBeTruthy();
  expect(seeded.request().headers().authorization).toMatch(/^Bearer /);
  const plan = await planResponse;
  expect(plan.ok()).toBeTruthy();
  expect(plan.request().headers().authorization).toMatch(/^Bearer /);

  await expect(page.getByRole("button", { name: "80% 계획 선택" })).toBeVisible();
  const selectedResponse = page.waitForResponse(
    (response) =>
      /\/api\/v1\/plan-versions\/\d+\/select-option$/.test(response.url()) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "80% 계획 선택" }).click();
  const selected = await selectedResponse;
  expect(selected.ok()).toBeTruthy();
  expect(selected.request().headers().authorization).toMatch(/^Bearer /);
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeVisible();
});

test("real HTTPS Core: refresh, relogin, and cross-user read/write/decision denial", async ({
  browser,
  context,
  page,
}) => {
  const suffix = `${Date.now()}-${test.info().parallelIndex}`;
  const accessToken = await login(page, context, `web-a-${suffix}`);

  expect(
    (
      await browserFetch(page, "/api/v1/me/profile", {
        method: "PUT",
        token: accessToken,
        body: { birthDate: "1995-05-15", regionCode: "11680" },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await browserFetch(page, "/api/v1/me/financial-profile", {
        method: "PUT",
        token: accessToken,
        body: { monthlyIncome: 4_200_000, monthlyFixedCost: 1_650_000 },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await browserFetch(page, "/api/v1/transactions/import", {
        method: "POST",
        token: accessToken,
        body: {
          transactions: [4, 3, 2].map((monthsAgo, index) => ({
            transactionAt: completedMonthDate(monthsAgo),
            amount: 1_200_000 + index * 100_000,
            transactionType: "PAYMENT",
            category: "E2E 검증",
            sourceId: "E2E",
            externalTransactionId: `bootstrap-${suffix}-${index}`,
          })),
        },
      })
    ).status,
  ).toBe(200);
  const createdGoal = await browserFetch(page, "/api/v1/goals", {
    method: "POST",
    token: accessToken,
    body: {
      name: "E2E 목표",
      targetAmount: 12_000_000,
      currentSavedAmount: 2_000_000,
      targetDate: futureDate(12),
    },
  });
  expect(createdGoal.status).toBe(201);
  const createdGoalId = (createdGoal.body as { id: number }).id;
  const plan = await browserFetch(page, `/api/v1/goals/${createdGoalId}/plan-versions`, {
    method: "POST",
    token: accessToken,
    body: { generationType: "INITIAL" },
  });
  expect(plan.status).toBe(200);
  const options = (plan.body as { id: number; options: { id: number }[] }).options;
  expect(options).toHaveLength(3);
  expect(
    (
      await browserFetch(
        page,
        `/api/v1/plan-versions/${(plan.body as { id: number }).id}/select-option`,
        { method: "POST", token: accessToken, body: { planOptionId: options[1].id } },
      )
    ).status,
  ).toBe(200);

  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeVisible();

  const me = await browserFetch(page, "/api/v1/me", { token: accessToken });
  expect(me.status).toBe(200);
  const { activeGoalId: goalId } = me.body as { activeGoalId: number };
  expect(goalId).toBeTruthy();

  const invalidTransactionDate = await browserFetch(page, "/api/v1/transactions/import", {
    method: "POST",
    token: accessToken,
    body: {
      transactions: [
        {
          transactionAt: "2026-02-31T12:00:00+09:00",
          amount: 1,
          transactionType: "PAYMENT",
          category: "E2E 검증",
          sourceId: "E2E",
          externalTransactionId: `invalid-date-${suffix}`,
        },
      ],
    },
  });
  expect(invalidTransactionDate.status).toBe(400);
  const invalidGoalDate = await browserFetch(page, `/api/v1/goals/${goalId}`, {
    method: "PATCH",
    token: accessToken,
    body: { targetDate: "2028-02-31" },
  });
  expect(invalidGoalDate.status).toBe(400);

  const externalTransactionId = `e2e-${suffix}`;
  const imported = await browserFetch(page, "/api/v1/transactions/import", {
    method: "POST",
    token: accessToken,
    body: {
      transactions: [
        {
          transactionAt: completedMonthDate(),
          amount: 12_345,
          transactionType: "PAYMENT",
          category: "E2E 검증",
          sourceId: "E2E",
          externalTransactionId,
        },
      ],
    },
  });
  expect(imported.status).toBe(200);
  expect(imported.body).toMatchObject({ inserted: 1 });
  const transactions = await browserFetch(page, "/api/v1/transactions?limit=200", {
    token: accessToken,
  });
  expect(transactions.status).toBe(200);
  expect(
    (transactions.body as { items: Array<Record<string, unknown>> }).items,
  ).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ sourceId: "E2E", externalTransactionId, amount: 12_345 }),
    ]),
  );

  const goalBefore = await browserFetch(page, `/api/v1/goals/${goalId}`, {
    token: accessToken,
  });
  expect(goalBefore.status).toBe(200);
  const previousTarget = (goalBefore.body as { targetAmount: number }).targetAmount;
  const goalUpdated = await browserFetch(page, `/api/v1/goals/${goalId}`, {
    method: "PATCH",
    token: accessToken,
    body: { targetAmount: previousTarget + 1 },
  });
  expect(goalUpdated.status).toBe(200);
  const goalEventId = (goalUpdated.body as { triggeredReplanEventId: number })
    .triggeredReplanEventId;
  expect(goalEventId).toBeTruthy();
  const goalAfter = await browserFetch(page, `/api/v1/goals/${goalId}`, {
    token: accessToken,
  });
  expect(goalAfter.body).toMatchObject({ id: goalId, targetAmount: previousTarget + 1 });
  const goalDecision = await browserFetch(page, `/api/v1/replan-events/${goalEventId}/decision`, {
    method: "POST",
    token: accessToken,
    body: { decision: "KEEP_CURRENT_PLAN" },
  });
  expect(goalDecision.status).toBe(200);

  const scheduled = await browserFetch(page, "/api/v1/scheduled-expenses", {
    method: "POST",
    token: accessToken,
    body: { name: "보험 갱신", amount: 120_000, scheduledDate: futureDate(2) },
  });
  expect(scheduled.status).toBe(201);
  const {
    id: scheduledExpenseId,
    triggeredReplanEventId: createdEventId,
  } = scheduled.body as { id: number; triggeredReplanEventId: number };
  expect(createdEventId).toBeTruthy();
  const createdDecision = await browserFetch(
    page,
    `/api/v1/replan-events/${createdEventId}/decision`,
    {
      method: "POST",
      token: accessToken,
      body: { decision: "KEEP_CURRENT_PLAN" },
    },
  );
  expect(createdDecision.status).toBe(200);

  const updated = await browserFetch(page, `/api/v1/scheduled-expenses/${scheduledExpenseId}`, {
    method: "PATCH",
    token: accessToken,
    body: { amount: 130_000 },
  });
  expect(updated.status).toBe(200);
  const { triggeredReplanEventId: updatedEventId } = updated.body as {
    triggeredReplanEventId: number;
  };
  expect(updatedEventId).toBeTruthy();
  const updatedDecision = await browserFetch(
    page,
    `/api/v1/replan-events/${updatedEventId}/decision`,
    {
      method: "POST",
      token: accessToken,
      body: { decision: "KEEP_CURRENT_PLAN" },
    },
  );
  expect(updatedDecision.status).toBe(200);

  const scheduledList = await browserFetch(page, "/api/v1/scheduled-expenses?status=PLANNED", {
    token: accessToken,
  });
  expect(scheduledList.status).toBe(200);
  expect(scheduledList.body as Array<Record<string, unknown>>).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ id: scheduledExpenseId, name: "보험 갱신", amount: 130_000 }),
    ]),
  );

  const events = await browserFetch(page, `/api/v1/goals/${goalId}/replan-events`, {
    token: accessToken,
  });
  expect(events.status).toBe(200);
  expect((events.body as Array<{ id: number }>).map(({ id }) => id)).toEqual(
    expect.arrayContaining([createdEventId, updatedEventId]),
  );
  const retryDecided = await browserFetch(page, `/api/v1/replan-events/${updatedEventId}/retry`, {
    method: "POST",
    token: accessToken,
  });
  expect(retryDecided.status).toBe(409);

  const replanResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/v1/goals/${goalId}/replan`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "지금 재계획하기" }).click();
  const replan = await replanResponse;
  expect(replan.ok()).toBeTruthy();
  const {
    id: planVersionId,
    replanEventId,
    options: replanOptions,
  } = (await replan.json()) as {
    id: number;
    replanEventId: number;
    options: Array<{ id: number; nominalLevel: number | null }>;
  };
  expect(planVersionId).toBeTruthy();
  expect(replanEventId).toBeTruthy();
  const planOptionId = replanOptions.find((option) => option.nominalLevel === 0.8)?.id;
  expect(planOptionId).toBeTruthy();
  await expect(page.getByRole("button", { name: /새 계획 선택/ })).toHaveCount(3);

  const otherContext = await browser.newContext({ baseURL });
  const otherPage = await otherContext.newPage();
  try {
    const otherAccessToken = await login(otherPage, otherContext, `web-b-${suffix}`);
    await denied(
      browserFetch(otherPage, `/api/v1/goals/${goalId}`, { token: otherAccessToken }),
    );
    await denied(
      browserFetch(otherPage, `/api/v1/goals/${goalId}`, {
        method: "PATCH",
        token: otherAccessToken,
        body: { targetAmount: 1 },
      }),
    );
    await denied(
      browserFetch(otherPage, `/api/v1/scheduled-expenses/${scheduledExpenseId}`, {
        method: "PATCH",
        token: otherAccessToken,
        body: { amount: 1 },
      }),
    );
    await denied(
      browserFetch(otherPage, `/api/v1/plan-versions/${planVersionId}`, {
        token: otherAccessToken,
      }),
    );
    await denied(
      browserFetch(otherPage, `/api/v1/plan-versions/${planVersionId}/select-option`, {
        method: "POST",
        token: otherAccessToken,
        body: { planOptionId },
      }),
    );
    await denied(
      browserFetch(otherPage, `/api/v1/replan-events/${replanEventId}/decision`, {
        method: "POST",
        token: otherAccessToken,
        body: { decision: "KEEP_CURRENT_PLAN" },
      }),
    );
  } finally {
    await otherContext.close();
  }

  const acceptedDecisionResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/v1/replan-events/${replanEventId}/decision`) &&
      response.request().method() === "POST",
  );
  const selectedOptionResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/v1/plan-versions/${planVersionId}/select-option`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "80% 새 계획 선택" }).click();
  expect((await acceptedDecisionResponse).ok()).toBeTruthy();
  expect((await selectedOptionResponse).ok()).toBeTruthy();
  await expect(page.getByText("기존 계획은 새 계획을 선택하기 전까지 유지됩니다.")).toBeVisible();
  const finalDashboard = await browserFetch(page, "/api/v1/dashboard", { token: accessToken });
  expect(finalDashboard.status).toBe(200);
  expect(finalDashboard.body).toMatchObject({ activePlan: { id: planVersionId } });

  const logoutResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/auth/logout") && response.request().method() === "POST",
  );
  const logout = await browserFetch(page, "/api/v1/auth/logout", { method: "POST" });
  expect(logout.status).toBe(204);
  const expiredRefreshCookie = (await (await logoutResponse).headersArray()).find(
      ({ name, value }) =>
        name.toLowerCase() === "set-cookie" && value.startsWith("refresh_token="),
    )?.value;
  expect(expiredRefreshCookie).toBeTruthy();
  expect(expiredRefreshCookie).toMatch(/(?:^|;\s*)Max-Age=0(?:;|$)/i);
  expect(expiredRefreshCookie).toMatch(/(?:^|;\s*)Path=\/api\/v1\/auth(?:;|$)/i);
  expect(expiredRefreshCookie).toMatch(/(?:^|;\s*)Secure(?:;|$)/i);
  expect(expiredRefreshCookie).toMatch(/(?:^|;\s*)HttpOnly(?:;|$)/i);
  expect(expiredRefreshCookie).toMatch(/(?:^|;\s*)SameSite=Strict(?:;|$)/i);
  expect((await context.cookies()).some(({ name }) => name === "refresh_token")).toBe(false);
  const refreshAfterLogout = await browserFetch(page, "/api/v1/auth/refresh", { method: "POST" });
  expect(refreshAfterLogout.status).toBe(401);
  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
  await login(page, context, `web-a-${suffix}`);
  await page.reload();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("img", { name: /목표까지의 저축 예상 범위/ })).toBeVisible();
  await expect(page.getByRole("region", { name: "현재 계획 지표" })).toBeVisible();
  const dashboardAfterRelogin = await browserFetch(page, "/api/v1/dashboard", {
    token: accessToken,
  });
  expect(dashboardAfterRelogin.status).toBe(200);
  expect(dashboardAfterRelogin.body).toMatchObject({ activePlan: { status: "ACTIVE" } });
});
