import { type FormEvent, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";
import {
  parseDemoSeedResponse,
  parseDemoTesters,
  parseId,
  parseImport,
  parseMe,
  parseObject,
  parsePlanSummaries,
  parsePlanVersion,
  type PlanVersion,
  type DemoTester,
} from "./types";

type TransactionInput = {
  transactionAt: string;
  amount: number;
  transactionType: "PAYMENT";
  category: string;
  sourceId: "MANUAL";
  externalTransactionId: string;
};
const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });
const won = new Intl.NumberFormat("ko-KR", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0,
});

const safeInteger = (value: FormDataEntryValue | null, label: string, minimum: number): number => {
  const text = typeof value === "string" ? value.trim() : "";
  const parsed = Number(text);
  if (!text || !Number.isSafeInteger(parsed) || parsed < minimum)
    throw new Error(`${label}은 안전하게 처리할 수 있는 정수 범위로 입력해 주세요.`);
  return parsed;
};

const requiredText = (value: FormDataEntryValue | null, label: string): string => {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text) throw new Error(`${label}을 입력해 주세요.`);
  return text;
};

const isCalendarDate = (value: string): boolean => {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return date.toISOString().slice(0, 10) === value;
};

const kstDate = (): string => {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
};

const transactions = (values: FormData): TransactionInput[] => {
  const currentMonth = kstDate().slice(0, 7);
  const result = [0, 1, 2].map((index) => {
    const dateValue = values.get(`transactionDate${index}`);
    const categoryValue = values.get(`transactionCategory${index}`);
    const date = typeof dateValue === "string" ? dateValue : "";
    const category = typeof categoryValue === "string" ? categoryValue.trim() : "";
    if (!isCalendarDate(date)) throw new Error("유효한 거래일을 입력해 주세요.");
    if (!category) throw new Error("거래 분류를 입력해 주세요.");
    if (date.slice(0, 7) >= currentMonth)
      throw new Error("거래는 완전히 끝난 달의 내역만 입력해 주세요.");
    const transaction: TransactionInput = {
      transactionAt: `${date}T00:00:00+09:00`,
      amount: safeInteger(values.get(`transactionAmount${index}`), "거래 금액", 1),
      transactionType: "PAYMENT",
      category,
      sourceId: "MANUAL",
      externalTransactionId: `manual-${index + 1}-${date}`,
    };
    return transaction;
  });
  if (new Set(result.map((item) => item.transactionAt.slice(0, 7))).size < 3)
    throw new Error("서로 다른 3개월의 거래가 필요합니다.");
  return result;
};

const failureMessage = (reason: unknown): string => {
  if (reason instanceof Error && !(reason instanceof ApiError)) return reason.message;
  if (!(reason instanceof ApiError)) return "응답을 처리하지 못했습니다. 다시 시도해 주세요.";
  if (reason.status === 422)
    return "현재 조건으로는 목표에 닿기 어렵습니다. 목표 금액이나 날짜를 조정해 주세요.";
  if (reason.status === 0 || reason.status >= 500)
    return `${reason.requestId ? `요청 ID ${reason.requestId}. ` : ""}연결하지 못했습니다. 입력값은 그대로 보관했습니다. 다시 시도해 주세요.`;
  return reason.message || "입력 내용을 확인해 주세요.";
};

const comparisonPlan = (value: unknown): PlanVersion => {
  const plan = parsePlanVersion(value);
  const levels = new Set(
    plan.options
      .filter((option) => option.optionType === "PRESET")
      .map((option) => option.nominalLevel),
  );
  if (![0.7, 0.8, 0.9].every((level) => levels.has(level)))
    throw new Error("서버 응답에 70/80/90 계획이 모두 필요합니다.");
  return plan;
};

export const OnboardingPage = ({ api }: { api: Pick<ApiClient, "get" | "post" | "put"> }) => {
  const navigate = useNavigate();
  const busyRef = useRef(false);
  const operation = useRef(0);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const [direct, setDirect] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [demoState, setDemoState] = useState<"loading" | "ready" | "error">("loading");
  const [demoTesters, setDemoTesters] = useState<DemoTester[]>([]);
  const [activeGoalId, setActiveGoalId] = useState<number | null>(null);
  const [plan, setPlan] = useState<PlanVersion | null>(null);

  useEffect(
    () => () => {
      operation.current += 1;
    },
    [],
  );
  useEffect(() => {
    if (error) errorRef.current?.focus();
  }, [error]);
  useEffect(() => {
    let current = true;
    setDemoState("loading");
    void (async () => {
      try {
        const value = await api.get("/demo/testers", parseDemoTesters);
        if (!current) return;
        setDemoTesters(parseDemoTesters(value));
        setDemoState("ready");
      } catch {
        if (current) setDemoState("error");
      }
    })();
    return () => {
      current = false;
    };
  }, [api]);

  const latestPlan = async (goalId: number): Promise<PlanVersion> => {
    const summaries = parsePlanSummaries(
      await api.get(`/goals/${goalId}/plan-versions?status=PROPOSED`, parsePlanSummaries),
    );
    if (summaries.length === 0) throw new Error("최신 제안 계획을 찾지 못했습니다.");
    return comparisonPlan(await api.get(`/plan-versions/${summaries[0].id}`, parsePlanVersion));
  };

  const createPlan = async (goalId: number): Promise<PlanVersion> => {
    try {
      return comparisonPlan(
        await api.post(
          `/goals/${goalId}/plan-versions`,
          { generationType: "INITIAL" },
          parsePlanVersion,
        ),
      );
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) return latestPlan(goalId);
      throw reason;
    }
  };

  const run = async (action: () => Promise<PlanVersion>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError("");
    const currentOperation = ++operation.current;
    try {
      const nextPlan = await action();
      if (operation.current === currentOperation) setPlan(nextPlan);
    } catch (reason) {
      if (operation.current === currentOperation) {
        if (reason instanceof ApiError && reason.status === 403)
          navigate("/forbidden", { replace: true });
        else if (reason instanceof ApiError && reason.status === 404)
          navigate("/not-found", { replace: true });
        else setError(failureMessage(reason));
      }
    } finally {
      if (operation.current === currentOperation) setBusy(false);
      busyRef.current = false;
    }
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    await run(async () => {
      let goalId = activeGoalId;
      if (goalId === null) {
        const monthlyIncome = safeInteger(values.get("monthlyIncome"), "월 소득", 0);
        const monthlyFixedCost = safeInteger(values.get("monthlyFixedCost"), "월 고정비", 0);
        const targetAmount = safeInteger(values.get("targetAmount"), "목표 금액", 1);
        const currentSavedAmount = safeInteger(
          values.get("currentSavedAmount"),
          "현재 모은 금액",
          0,
        );
        const importedTransactions = transactions(values);
        const goalName = requiredText(values.get("goalName"), "목표 이름");
        const targetDate = requiredText(values.get("targetDate"), "목표 날짜");
        if (!isCalendarDate(targetDate)) throw new Error("유효한 목표 날짜를 입력해 주세요.");
        if (targetDate <= kstDate()) throw new Error("목표 날짜는 오늘보다 미래여야 합니다.");
        await api.put(
          "/me/profile",
          {
            birthDate: values.get("birthDate") || undefined,
            regionCode: values.get("regionCode") || undefined,
          },
          parseObject,
        );
        try {
          await api.put(
            "/me/financial-profile",
            {
              monthlyIncome,
              monthlyFixedCost,
            },
            parseObject,
          );
        } catch (reason) {
          if (!(reason instanceof ApiError) || reason.status !== 409) throw reason;
          await api.get("/me/financial-profile", parseObject);
          const me = parseMe(await api.get("/me", parseMe));
          goalId = me.activeGoalId;
          if (goalId !== null) setActiveGoalId(goalId);
        }
        await api.post("/transactions/import", { transactions: importedTransactions }, parseImport);
        if (goalId === null) {
          const goal = parseId(
            await api.post(
              "/goals",
              {
                name: goalName,
                targetAmount,
                currentSavedAmount,
                targetDate,
              },
              parseId,
            ),
          );
          goalId = goal.id;
          setActiveGoalId(goalId);
        }
      }
      return createPlan(goalId);
    });
  };

  const selectDemo = async (testerId: string) => {
    await run(async () => {
      let goalId = activeGoalId;
      if (goalId === null) {
        parseDemoSeedResponse(await api.post("/me/demo-seed", { testerId }, parseDemoSeedResponse));
        const me = parseMe(await api.get("/me", parseMe));
        if (me.activeGoalId === null) throw new Error("데모 목표를 찾지 못했습니다.");
        goalId = me.activeGoalId;
        setActiveGoalId(goalId);
      }
      return createPlan(goalId);
    });
  };

  const selectOption = async (optionId: number) => {
    if (!plan) return;
    await run(async () => {
      try {
        const selected = parsePlanVersion(
          await api.post(
            `/plan-versions/${plan.id}/select-option`,
            { planOptionId: optionId },
            parsePlanVersion,
          ),
        );
        navigate("/dashboard", { replace: true });
        return selected;
      } catch (reason) {
        if (reason instanceof ApiError && reason.status === 409 && activeGoalId !== null)
          return latestPlan(activeGoalId);
        throw reason;
      }
    });
  };

  if (plan)
    return (
      <main className="onboarding-shell">
        <header>
          <p className="eyebrow">계획 비교</p>
          <h1>유지할 수 있는 항로를 고르세요</h1>
          <p>금액과 시뮬레이션 충족률을 함께 확인한 뒤 명시적으로 선택합니다.</p>
        </header>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <section className="plan-options" aria-label="계획 선택지">
          {plan.options
            .filter((option) => option.optionType === "PRESET")
            .map((option) => (
              <article key={option.id}>
                <p className="eyebrow">
                  {option.nominalLevel === null ? "사용자" : percent.format(option.nominalLevel)}{" "}
                  안정성 수준
                </p>
                <h2>{won.format(option.recommendedMonthlySpending)}</h2>
                <p>월 유동지출 · 시뮬레이션 충족률 {percent.format(option.simulationCoverage)}</p>
                {option.aggressiveWarning && (
                  <p className="warning-text">주의: 최근 소비보다 상당히 낮습니다.</p>
                )}
                <button
                  className="primary"
                  disabled={busy}
                  onClick={() => void selectOption(option.id)}
                >
                  {option.nominalLevel === null
                    ? "사용자 계획 선택"
                    : `${percent.format(option.nominalLevel)} 계획 선택`}
                </button>
              </article>
            ))}
        </section>
      </main>
    );

  if (!direct)
    return (
      <main className="onboarding-shell">
        <header>
          <p className="brand-mark">ODYSSEY / 출발점</p>
          <h1>어떤 항로로 시작할까요?</h1>
          <p>내 정보로 첫 계획을 만들 수 있습니다.</p>
        </header>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <section className="start-options" aria-label="시작 항로">
          {demoState === "loading" && (
            <article aria-busy="true">
              <p className="eyebrow">데모 항로</p>
              <h2>데모 항로를 불러오고 있습니다</h2>
            </article>
          )}
          {demoState === "error" && (
            <article role="alert">
              <p className="eyebrow">데모 항로</p>
              <h2>데모 항로를 불러오지 못했습니다</h2>
              <p>직접 입력으로 계속 시작할 수 있습니다.</p>
            </article>
          )}
          {demoState === "ready" && demoTesters.length === 0 && (
            <article>
              <p className="eyebrow">데모 항로</p>
              <h2>지금 선택할 수 있는 데모 항로가 없습니다.</h2>
              <p>직접 입력으로 첫 계획을 만들어 주세요.</p>
            </article>
          )}
          {demoTesters.map((tester) => (
            <article key={tester.testerId}>
              <p className="eyebrow">{tester.ageGroup}</p>
              <h2>{tester.displayName}</h2>
              <p>{tester.description}</p>
              <p>
                월 소득 {won.format(tester.monthlyIncome)} · 고정비{" "}
                {won.format(tester.monthlyFixedCost)}
              </p>
              <p>
                {tester.goalName} · {won.format(tester.goalTargetAmount)} · {tester.goalMonths}개월
              </p>
              <button
                className="primary"
                disabled={busy}
                onClick={() => void selectDemo(tester.testerId)}
              >
                {tester.displayName}으로 시작하기
              </button>
            </article>
          ))}
          <article>
            <p className="eyebrow">내 계획</p>
            <h2>직접 항로 만들기</h2>
            <p>월 소득과 고정비, 최소 3개월 거래로 계산합니다.</p>
            <button className="primary" disabled={busy} onClick={() => setDirect(true)}>
              직접 시작하기
            </button>
          </article>
        </section>
      </main>
    );

  return (
    <main className="onboarding-shell narrow">
      <header>
        <p className="eyebrow">출발 정보</p>
        <h1>지금의 현실에서 시작합니다</h1>
        <p>금액은 원 단위 정수로 입력해 주세요.</p>
      </header>
      <form className="onboarding-form" onSubmit={(event) => void submit(event)}>
        <fieldset>
          <legend>나의 기준</legend>
          <div className="field-row">
            <label>
              생년월일
              <input name="birthDate" type="date" />
            </label>
            <label>
              지역 코드
              <input
                name="regionCode"
                inputMode="numeric"
                pattern="[0-9]{5}"
                maxLength={5}
                placeholder="시군구 5자리"
              />
            </label>
          </div>
        </fieldset>
        <fieldset>
          <legend>한 달의 생활</legend>
          <div className="field-row">
            <label>
              월 소득
              <input name="monthlyIncome" type="number" min="0" step="1" required />
            </label>
            <label>
              월 고정비
              <input name="monthlyFixedCost" type="number" min="0" step="1" required />
            </label>
          </div>
        </fieldset>
        <fieldset>
          <legend>최근 거래 · 서로 다른 3개월</legend>
          {[0, 1, 2].map((index) => (
            <div className="transaction-row" key={index}>
              <label>
                거래일 {index + 1}
                <input name={`transactionDate${index}`} type="date" required />
              </label>
              <label>
                거래 금액 {index + 1}
                <input name={`transactionAmount${index}`} type="number" min="1" step="1" required />
              </label>
              <label>
                거래 분류 {index + 1}
                <input name={`transactionCategory${index}`} required />
              </label>
            </div>
          ))}
        </fieldset>
        <fieldset>
          <legend>첫 번째 목적지</legend>
          <label>
            목표 이름
            <input name="goalName" required minLength={1} maxLength={100} />
          </label>
          <div className="field-row">
            <label>
              목표 금액
              <input name="targetAmount" type="number" min="1" step="1" required />
            </label>
            <label>
              현재 모은 금액
              <input
                name="currentSavedAmount"
                type="number"
                min="0"
                step="1"
                defaultValue="0"
                required
              />
            </label>
          </div>
          <label>
            목표 날짜
            <input name="targetDate" type="date" required />
          </label>
        </fieldset>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <button className="primary full" disabled={busy}>
          {busy
            ? "계산하고 있습니다"
            : activeGoalId === null
              ? "계획 만들기"
              : "계획 다시 계산하기"}
        </button>
      </form>
    </main>
  );
};
