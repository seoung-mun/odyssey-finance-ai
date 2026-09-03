import { type FormEvent, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";
import { sidoRegions, sigunguRegions } from "./data/regionCodes";
import { formatMoneyCompact } from "./formatMoney";
import {
  parseDemoSeedResponse,
  parseDemoTransactionsResponse,
  parseDemoTesters,
  parseCategorySpending,
  parseId,
  parseMe,
  parseMonthlySpending,
  parseObject,
  parsePlanOption,
  parsePlanSummaries,
  parsePlanVersion,
  parseScheduledExpense,
  parseScheduledExpenses,
  type ScheduledExpense,
  type PlanVersion,
  type DemoTester,
  type CategorySpending,
  type MonthlySpending,
} from "./types";

const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });
const demoPatternLabels = {
  youth: "변동 소비형",
  middle: "균형 소비형",
  senior: "안정 소비형",
} as const;

const koreanWon = (value: string): string => {
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount < 10_000) return "";
  return formatMoneyCompact(amount);
};

const remainingMonths = (targetDate: string): number | null => {
  if (!isCalendarDate(targetDate)) return null;
  const [year, month] = targetDate.split("-").map(Number);
  const [currentYear, currentMonth] = kstDate().split("-").map(Number);
  return Math.max(0, (year - currentYear) * 12 + month - currentMonth);
};

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

const planCopy = (nominalLevel: number | null) => {
  if (nominalLevel === null)
    return ["맞춤 계획", "내가 정한 월 생활비 기준으로 계획 안정성을 다시 계산했어요."];
  if (nominalLevel === 0.7)
    return ["소비 여유형", "매달 쓸 수 있는 금액을 가장 넉넉하게 잡았어요."];
  if (nominalLevel === 0.8) return ["균형형", "소비 여유와 계획 안정성을 함께 고려했어요."];
  return ["목표 우선형", "월 사용 금액을 낮춰 계획 안정성을 높였어요."];
};

const categoryDisplay = (category: string): { label: string; icon: string } => {
  const displays: Record<string, { label: string; icon: string }> = {
    food: { label: "식비", icon: "🍱" },
    transport: { label: "교통", icon: "🚇" },
    shopping: { label: "쇼핑", icon: "🛍" },
    leisure: { label: "여가", icon: "🎬" },
    health: { label: "의료·건강", icon: "🩺" },
    other: { label: "기타", icon: "💡" },
  };
  const normalized = category.trim().toLowerCase();
  return displays[normalized] ?? {
    label: /[가-힣]/.test(category) ? category : "기타",
    icon: "💡",
  };
};

const planSteps = ["목표 설정", "예정지출", "마이데이터", "계획 생성"];
const ProgressStepper = ({ current }: { current: number }) => {
  const nodeX = (index: number) => 40 + (400 / (planSteps.length - 1)) * index;
  const shipX = nodeX(current);
  return (
    <nav className="onboarding-route" aria-label="계획 생성 단계">
      <svg viewBox="0 0 480 48" role="img" aria-label="계획 생성 항로">
        <line className="onboarding-route-line" x1="40" y1="24" x2="440" y2="24" />
        <line className="onboarding-route-progress" x1="40" y1="24" x2={shipX} y2="24" />
        {planSteps.map((step, index) => {
          const state = index < current ? "complete" : index === current ? "current" : "upcoming";
          return (
            <g key={step} className={`onboarding-step-${state}`}>
              <circle cx={nodeX(index)} cy="24" r="11" />
              {state === "complete" && <path d={`M${nodeX(index) - 5} 24l4 4 7-8`} fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" />}
              {state === "current" && <circle cx={nodeX(index)} cy="24" r="3.5" fill="white" />}
            </g>
          );
        })}
        <g className="onboarding-route-boat-position" transform={`translate(${shipX} 24)`} aria-hidden="true">
          <g className="onboarding-route-boat">
            <path className="onboarding-route-wake" d="M-18 3c-6-2-10 1-15 0" />
            <path className="onboarding-route-hull" d="M-11 0h22l-4 7H-8z" />
            <line x1="1" y1="-18" x2="1" y2="1" stroke="#3840e0" strokeWidth="1.2" />
            <path className="onboarding-route-sail" d="M1-17 11 0H1z" />
            <path className="onboarding-route-sail" d="M0-13-9 0H0z" />
          </g>
        </g>
      </svg>
      <ol aria-label="계획 생성 단계" className="onboarding-steps">
        {planSteps.map((step, index) => (
          <li key={step} className={`onboarding-step-${index < current ? "complete" : index === current ? "current" : "upcoming"}`} aria-current={index === current ? "step" : undefined}>{step}</li>
        ))}
      </ol>
    </nav>
  );
};

const MoneyField = ({
  label,
  name,
  minimum,
  value,
  defaultValue,
  onChange,
}: {
  label: string;
  name: string;
  minimum: number;
  value: string;
  defaultValue?: string;
  onChange: (value: string) => void;
}) => (
  <div className="money-field">
    <label>
      {label}
      <input
        name={name}
        type="number"
        min={minimum}
        step="1"
        defaultValue={defaultValue}
        required
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
    <span className="money-input-suffix" aria-hidden="true">
      원
    </span>
    {koreanWon(value) && <p className="money-hint">{koreanWon(value)}</p>}
  </div>
);

export const OnboardingPage = ({ api }: { api: Pick<ApiClient, "get" | "post" | "put"> & Partial<Pick<ApiClient, "patch">> }) => {
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
  const [selectedOptionId, setSelectedOptionId] = useState<number | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [spendingDetail, setSpendingDetail] = useState(false);
  const [spendingState, setSpendingState] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [categorySpending, setCategorySpending] = useState<CategorySpending | null>(null);
  const [monthlySpending, setMonthlySpending] = useState<MonthlySpending[]>([]);
  const [customSpending, setCustomSpending] = useState("");
  const [moneyHints, setMoneyHints] = useState<Record<string, string>>({});
  const [targetDateHint, setTargetDateHint] = useState("");
  const [selectedSido, setSelectedSido] = useState("");
  const [directStep, setDirectStep] = useState<3 | 4 | 5>(3);
  const [scheduled, setScheduled] = useState<ScheduledExpense[]>([]);
  const [scheduleState, setScheduleState] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [editingExpenseId, setEditingExpenseId] = useState<number | null>(null);
  const [addingExpense, setAddingExpense] = useState(false);
  const [scheduleBusy, setScheduleBusy] = useState(false);
  const [birthDate, setBirthDate] = useState("");
  const [regionCode, setRegionCode] = useState("");
  const [goalName, setGoalName] = useState("");
  const [demoGoalName, setDemoGoalName] = useState("");
  const [demoTransactions, setDemoTransactions] = useState<ReturnType<
    typeof parseDemoTransactionsResponse
  > | null>(null);

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
    if (direct) {
      document.documentElement.scrollTop = 0;
      document.body.scrollTop = 0;
    }
  }, [direct]);
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
      if (operation.current === currentOperation) {
        setPlan(nextPlan);
        setSelectedOptionId(null);
        setConfirming(false);
      }
    } catch (reason) {
      if (operation.current === currentOperation) {
        if (reason instanceof ApiError && reason.status === 403)
          navigate("/forbidden", { replace: true });
        else if (reason instanceof ApiError && reason.status === 404)
          navigate("/not-found", { replace: true });
        else if (!(reason instanceof Error && reason.message === "STEP_COMPLETE")) setError(failureMessage(reason));
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
        const goalName = requiredText(values.get("goalName"), "목표 이름");
        const targetDate = requiredText(values.get("targetDate"), "목표 날짜");
        if (!isCalendarDate(targetDate)) throw new Error("유효한 목표 날짜를 입력해 주세요.");
        if (targetDate <= kstDate()) throw new Error("목표 날짜는 오늘보다 미래여야 합니다.");
        const birthDate = requiredText(values.get("birthDate"), "생년월일");
        const regionCode = requiredText(values.get("regionCode"), "지역 코드");
        if (!isCalendarDate(birthDate)) throw new Error("유효한 생년월일을 입력해 주세요.");
        if (!/^\d{5}$/.test(regionCode)) throw new Error("지역 코드는 시군구 5자리여야 합니다.");
        await api.put(
          "/me/profile",
          { birthDate, regionCode },
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
      setScheduleState("loading");
      try {
        setScheduled(parseScheduledExpenses(await api.get("/scheduled-expenses?status=PLANNED", parseScheduledExpenses)));
        setScheduleState("ready");
        setDirectStep(4);
      } catch (reason) {
        setScheduleState("error");
        throw reason;
      }
      throw new Error("STEP_COMPLETE");
    });
  };

  const addScheduled = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (scheduleBusy) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    setError("");
    setScheduleBusy(true);
    try {
      const scheduledDate = requiredText(values.get("scheduledDate"), "예정 날짜");
      if (!isCalendarDate(scheduledDate) || scheduledDate <= kstDate())
        throw new Error("예정 날짜는 오늘 이후여야 합니다.");
      if (targetDateHint && scheduledDate > targetDateHint)
        throw new Error("예정지출은 목표 날짜 이후일 수 없습니다.");
      const created = parseScheduledExpense(
        await api.post(
          "/scheduled-expenses",
          {
            name: requiredText(values.get("scheduledName"), "예정지출 이름"),
            amount: safeInteger(values.get("scheduledAmount"), "예정지출 금액", 1),
            scheduledDate,
          },
          parseScheduledExpense,
        ),
      );
      setScheduled((current) => [...current, created]);
      form.reset();
      setAddingExpense(false);
    } catch (reason) {
      setError(failureMessage(reason));
    } finally {
      setScheduleBusy(false);
    }
  };

  const cancelScheduled = async (item: ScheduledExpense) => {
    if (scheduleBusy) return;
    setError("");
    setScheduleBusy(true);
    try {
      if (!api.patch) throw new Error("예정지출 변경 기능을 사용할 수 없습니다.");
      await api.patch(
        `/scheduled-expenses/${item.id}`,
        { status: "CANCELLED" },
        parseScheduledExpense,
      );
      setScheduled((current) => current.filter(({ id }) => id !== item.id));
    } catch (reason) {
      setError(failureMessage(reason));
    } finally {
      setScheduleBusy(false);
    }
  };

  const editScheduled = async (event: FormEvent<HTMLFormElement>, item: ScheduledExpense) => {
    event.preventDefault();
    if (scheduleBusy) return;
    setError("");
    setScheduleBusy(true);
    try {
      if (!api.patch) throw new Error("예정지출 변경 기능을 사용할 수 없습니다.");
      const values = new FormData(event.currentTarget);
      const scheduledDate = requiredText(values.get("editScheduledDate"), "예정 날짜");
      if (
        !isCalendarDate(scheduledDate) ||
        scheduledDate <= kstDate() ||
        (targetDateHint && scheduledDate > targetDateHint)
      )
        throw new Error("예정 날짜는 오늘 이후, 목표 날짜 이하여야 합니다.");
      const updated = parseScheduledExpense(
        await api.patch(
          `/scheduled-expenses/${item.id}`,
          {
            name: requiredText(values.get("editScheduledName"), "예정지출 이름"),
            amount: safeInteger(values.get("editScheduledAmount"), "예정지출 금액", 1),
            scheduledDate,
          },
          parseScheduledExpense,
        ),
      );
      setScheduled((current) =>
        current.map((value) => (value.id === updated.id ? updated : value)),
      );
      setEditingExpenseId(null);
    } catch (reason) {
      setError(failureMessage(reason));
    } finally {
      setScheduleBusy(false);
    }
  };

  const applySample = async () => {
    if (activeGoalId === null) return;
    await run(async () => {
      const result = parseDemoTransactionsResponse(
        await api.post("/me/demo-transactions", undefined, parseDemoTransactionsResponse),
      );
      setDemoTransactions(result);
      return createPlan(activeGoalId);
    });
  };

  const selectDemo = async (testerId: string) => {
    await run(async () => {
      const tester = demoTesters.find((item) => item.testerId === testerId);
      setDemoGoalName(tester?.goalName ?? "");
      let goalId = activeGoalId;
      if (goalId === null) {
        parseDemoSeedResponse(await api.post("/me/demo-seed", { testerId }, parseDemoSeedResponse));
        const me = parseMe(await api.get("/me", parseMe));
        if (me.activeGoalId === null) throw new Error("데모 목표를 찾지 못했습니다.");
        goalId = me.activeGoalId;
        setActiveGoalId(goalId);
      }
      try {
        setScheduled(parseScheduledExpenses(await api.get("/scheduled-expenses?status=PLANNED", parseScheduledExpenses)));
      } catch {
        setScheduled([]);
      }
      return createPlan(goalId);
    });
  };

  const openSpendingDetail = async () => {
    setSpendingDetail(true);
    if (spendingState === "ready") return;
    setSpendingState("loading");
    setError("");
    try {
      const [categoryValue, monthlyValue] = await Promise.all([
        api.get("/transactions/category-summary?months=3", parseCategorySpending),
        api.get("/transactions/monthly-summary?months=6", parseMonthlySpending),
      ]);
      setCategorySpending(parseCategorySpending(categoryValue));
      setMonthlySpending(parseMonthlySpending(monthlyValue));
      setSpendingState("ready");
    } catch (reason) {
      setSpendingState("error");
      setError(failureMessage(reason));
    }
  };

  const createCustom = async () => {
    if (!plan || !/^\d+$/.test(customSpending) || Number(customSpending) < 0) {
      setError("최소 생활비를 0원 이상의 정수로 입력해 주세요.");
      return;
    }
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError("");
    try {
      const option = parsePlanOption(
        await api.post(
          `/plan-versions/${plan.id}/custom-option`,
          { monthlySpending: Number(customSpending) },
          parsePlanOption,
        ),
      );
      setPlan((current) =>
        current
          ? {
              ...current,
              options: [...current.options.filter((item) => item.optionType !== "CUSTOM"), option],
            }
          : current,
      );
      setSelectedOptionId(option.id);
      setSpendingDetail(false);
    } catch (reason) {
      setError(failureMessage(reason));
    } finally {
      setBusy(false);
      busyRef.current = false;
    }
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

  const selectedOption = plan?.options.find((option) => option.id === selectedOptionId) ?? null;
  const visibleOptions = plan
    ? [
        ...plan.options.filter((option) => option.optionType === "PRESET"),
        ...plan.options.filter((option) => option.optionType === "CUSTOM").slice(-1),
      ]
    : [];
  const plannedScheduled = scheduled.filter((item) => item.status === "PLANNED");
  const plannedScheduledTotal = plannedScheduled.reduce((total, item) => total + item.amount, 0);

  if (plan && selectedOption && spendingDetail) {
    const recentAverage = categorySpending?.currentAvgVariableSpending ?? plan.snapshot?.currentAvgVariableSpending ?? 0;
    const difference = recentAverage - selectedOption.recommendedMonthlySpending;
    const maxCategory = Math.max(1, ...(categorySpending?.categories.map((item) => item.monthlyAverage) ?? []));
    const maxMonthly = Math.max(1, ...monthlySpending.map((item) => item.adjustedConsumption));
    return (
      <main className="onboarding-shell spending-detail-screen">
        <header className="spending-detail-header">
          <h1>이 계획을 실제로 지킬 수 있을까요?</h1>
          <p>최근 소비와 선택한 계획의 차이를 먼저 확인해보세요.</p>
        </header>
        {error && <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">{error}</p>}
        <section className="spending-comparison" aria-label="최근 소비와 계획 비교">
          <div><span>최근 월평균 소비</span><strong>{formatMoneyCompact(recentAverage)}</strong></div>
          <div><span>선택한 {planCopy(selectedOption.nominalLevel)[0]}</span><strong>월 {formatMoneyCompact(selectedOption.recommendedMonthlySpending)}</strong><p>{difference > 0 ? `최근보다 월 ${formatMoneyCompact(difference)} 줄여야 해요.` : `최근 소비보다 월 ${formatMoneyCompact(Math.abs(difference))} 여유 있어요.`}</p></div>
        </section>
        <section className="spending-breakdown-card">
          <h2>카테고리별 월평균 소비</h2>
          {spendingState === "loading" && <p role="status" className="spending-status">소비내역을 불러오고 있습니다.</p>}
          {spendingState === "error" && <button className="secondary" onClick={() => { setSpendingState("idle"); void openSpendingDetail(); }}>소비내역 다시 불러오기</button>}
          {spendingState === "ready" && categorySpending && (
            <div className="category-spending-list">
              {categorySpending.categories.map((item) => (
                <div className="category-spending-row" key={item.category}>
                  <span className="category-emoji" aria-hidden="true">{categoryDisplay(item.category).icon}</span>
                  <span className="category-name">{categoryDisplay(item.category).label}</span>
                  <span className="category-bar"><i style={{ width: `${Math.max(4, item.monthlyAverage / maxCategory * 100)}%` }} /></span>
                  <strong>{formatMoneyCompact(item.monthlyAverage)}</strong>
                </div>
              ))}
              <div className="three-month-average"><span>최근 3개월 월평균</span><strong>{formatMoneyCompact(categorySpending.currentAvgVariableSpending)}</strong></div>
            </div>
          )}
          <div className="monthly-trend">
            <p>최근 6개월 추이</p>
            <div className="monthly-trend-bars">
              {monthlySpending.map((item, index) => (
                <div key={item.yearMonth} title={formatMoneyCompact(item.adjustedConsumption)}>
                  <i className={index === monthlySpending.length - 1 ? "latest" : ""} style={{ height: `${Math.max(5, item.adjustedConsumption / maxMonthly * 64)}px` }} />
                  <span>{Number(item.yearMonth.slice(5, 7))}월</span>
                </div>
              ))}
            </div>
          </div>
        </section>
        <section className="living-expense-card">
          <div><h2>매달 꼭 필요한 생활비는 얼마인가요?</h2><p>이번 맞춤 계획에서 유지하고 싶은 월 유동지출 기준입니다.</p></div>
          <label>최소 생활비<div className="living-expense-input"><input aria-label="최소 생활비" inputMode="numeric" value={customSpending ? Number(customSpending).toLocaleString("ko-KR") : ""} onChange={(event) => setCustomSpending(event.target.value.replace(/[^0-9]/g, ""))} /><span>원</span></div></label>
          {customSpending && <p className="living-expense-difference">선택한 계획보다 {formatMoneyCompact(Math.abs(Number(customSpending) - selectedOption.recommendedMonthlySpending))} {Number(customSpending) > selectedOption.recommendedMonthlySpending ? "높아요" : "여유 있어요"}</p>}
          <button className="primary custom-recalculate" disabled={busy || !customSpending} onClick={() => void createCustom()}>{busy ? "다시 계산하고 있습니다" : "이 기준으로 계획 다시 계산"}</button>
        </section>
        <button className="ui-demo-prev spending-back" onClick={() => { setError(""); setSpendingDetail(false); }}>← 계획 선택으로 돌아가기</button>
      </main>
    );
  }

  if (plan && selectedOption && confirming) {
    const [title] = planCopy(selectedOption.nominalLevel);
    const snapshot = plan.snapshot;
    return (
      <main className="onboarding-shell narrow plan-confirmation">
        <header className="confirmation-header">
          <p className="eyebrow">최종 확인</p>
          <h1>이 계획으로 시작할까요?</h1>
          <p>선택한 계획의 핵심 정보를 최종 확인해보세요.</p>
        </header>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <section className="confirmation-card ui-demo-confirmation-card" aria-label="선택한 계획 확인">
          <div className="confirmation-hero">
            <div>
              <p className="eyebrow">선택한 {title} 계획</p>
              <p>매달 쓸 수 있는 금액</p>
            </div>
            <strong>{formatMoneyCompact(selectedOption.recommendedMonthlySpending)}</strong>
          </div>
          <dl className="confirmation-metrics">
            <div>
              <dt>계획 안정성</dt>
              <dd>{percent.format(selectedOption.simulationCoverage)}</dd>
            </div>
            <div>
              <dt>필요 절감률</dt>
              <dd>{percent.format(selectedOption.requiredReductionRate)}</dd>
            </div>
            <div>
              <dt>과거 실현 가능성</dt>
              <dd>90%</dd>
            </div>
          </dl>
          {snapshot && (
            <section className="confirmation-goal">
              <h2>{goalName || demoGoalName || "내집마련"} 목표 현황</h2>
              <div className="confirmation-goal-track"><span style={{ width: `${Math.min(100, snapshot.targetAmount === 0 ? 0 : snapshot.currentSaved / snapshot.targetAmount * 100)}%` }} /></div>
              <dl>
                <div><dt>목표 금액</dt><dd>{formatMoneyCompact(snapshot.targetAmount)}</dd></div>
                <div><dt>현재 저축</dt><dd>{formatMoneyCompact(snapshot.currentSaved)}</dd></div>
                <div><dt>남은 기간</dt><dd>{snapshot.remainingMonths}개월</dd></div>
              </dl>
            </section>
          )}
          {selectedOption.aggressiveWarning && (
            <p className="plan-card-warning warning-text">주의: 최근 소비보다 상당히 낮습니다.</p>
          )}
        </section>
        {snapshot && (
          <section className="confirmation-reflections">
            <h2>계획에 반영한 내용</h2>
            <div><span>💰 월 소득</span><strong>{formatMoneyCompact(snapshot.monthlyIncome)}</strong></div>
            <div><span>📌 월 고정지출</span><strong>{formatMoneyCompact(snapshot.monthlyFixedCost)}</strong></div>
            <div className="confirmation-scheduled-expenses">
              <span>✈️ 예정지출 {plannedScheduled.length}건</span>
              <strong>{formatMoneyCompact(plannedScheduledTotal)}</strong>
              <small>{plannedScheduled.length ? plannedScheduled.map((item) => item.name).join(" · ") : "없음"}</small>
            </div>
          </section>
        )}
        <p className="confirmation-note">
          계획을 확정하면 대시보드에서 실제 소비와 목표 항로를 계속 확인할 수 있습니다.
        </p>
        <div className="confirmation-actions">
          <button className="secondary" disabled={busy} onClick={() => setConfirming(false)}>
            다시 선택
          </button>
          <button
            className="primary"
            disabled={busy}
            onClick={() => void selectOption(selectedOption.id)}
          >
            {busy ? "확정하고 있습니다" : "이 계획으로 시작"}
          </button>
        </div>
      </main>
    );
  }

  if (plan)
    return (
      <main className="onboarding-shell plan-compare-screen">
        <ProgressStepper current={3} />
        <header className="compare-header">
          <p className="eyebrow">계획 비교</p>
          <h1>매달 쓸 수 있는 금액을 선택해보세요</h1>
          <p>금액이 낮을수록 목표 달성 계획은 더 안정적이에요.</p>
        </header>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <section className="plan-options" aria-label="계획 선택지">
          {visibleOptions
            .map((option) => {
              const [title, description] = planCopy(option.nominalLevel);
              return (
                <article
                  key={option.id}
                  className={`plan-card${selectedOptionId === option.id ? " selected" : ""}${option.aggressiveWarning ? " warning" : ""}`}
                >
                  <svg
                    className="plan-card-wave"
                    aria-hidden="true"
                    viewBox="0 0 300 20"
                    preserveAspectRatio="none"
                  >
                    <path d="M0 10C30 4 60 16 90 10s60-6 90 0 60 6 120 0v10H0z" />
                  </svg>
                  <div className="plan-card-heading">
                    <p className="eyebrow">
                      {option.nominalLevel === null
                        ? "사용자"
                        : percent.format(option.nominalLevel)}{" "}
                      계획
                    </p>
                    <h2>{title}</h2>
                    <p>{description}</p>
                  </div>
                  <div className="plan-card-amount">
                    <p>월 유동지출</p>
                    <strong>{formatMoneyCompact(option.recommendedMonthlySpending)}</strong>
                  </div>
                  <div className="plan-card-coverage">
                    <span>계획 안정성</span>
                    <strong>{percent.format(option.simulationCoverage)}</strong>
                  </div>
                  <div
                    aria-label="계획 안정성"
                    aria-valuemax={100}
                    aria-valuemin={0}
                    aria-valuenow={Math.round(option.simulationCoverage * 100)}
                    className="plan-card-progress"
                    role="progressbar"
                  >
                    <span style={{ width: percent.format(option.simulationCoverage) }} />
                  </div>
                  <p className="plan-card-explanation">
                    계획 안정성은 과거 소비 변동을 반영한 시뮬레이션 충족률입니다.
                  </p>
                  {option.aggressiveWarning && (
                    <p className="plan-card-warning warning-text">
                      주의: 최근 소비보다 상당히 낮습니다.
                    </p>
                  )}
                  {option.aggressiveWarning && (
                    <button className="plan-detail-link" type="button" onClick={(event) => { event.stopPropagation(); setSelectedOptionId(option.id); void openSpendingDetail(); }}>
                      최근 소비내역 상세보기 →
                    </button>
                  )}
                  <button
                    className="plan-card-action primary"
                    disabled={busy}
                    aria-pressed={selectedOptionId === option.id}
                    onClick={() => setSelectedOptionId(option.id)}
                  >
                    {option.nominalLevel === null
                      ? selectedOptionId === option.id
                        ? "사용자 계획 선택됨"
                        : "사용자 계획 선택"
                      : `${percent.format(option.nominalLevel)} 계획 ${selectedOptionId === option.id ? "선택됨" : "선택"}`}
                  </button>
                </article>
              );
            })}
        </section>
        <aside className="plan-selection-summary">
          <div>
            <p className="eyebrow">선택한 계획</p>
            <p>
              {selectedOption
                ? `${planCopy(selectedOption.nominalLevel)[0]} · 월 ${formatMoneyCompact(selectedOption.recommendedMonthlySpending)}`
                : "카드를 선택해 비교해 주세요."}
            </p>
          </div>
          <button
            className="primary"
            disabled={busy || !selectedOption}
            onClick={() => setConfirming(true)}
          >
            선택한 계획 확인하기
          </button>
        </aside>
        <p className="plan-context-note">계획 안정성은 과거 소비 변동을 반영한 시뮬레이션에서 해당 계획의 소비 기준을 충족하는 정도를 나타냅니다.</p>
      </main>
    );

  if (!direct)
    return (
      <main className="onboarding-shell">
        <header className="start-header">
          <p className="brand-mark">ODYSSEY / 출발점</p>
          <h1>나에게 맞는 시작 방식을 골라보세요</h1>
          <p>데모 시나리오를 둘러보거나, 내 정보로 첫 계획을 만들 수 있습니다.</p>
        </header>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <section className="start-options" aria-label="시작 항로">
          <div className="start-option-heading">
            <p className="eyebrow">데모로 둘러보기</p>
            <h2>데모 시나리오를 선택하세요</h2>
            <p>월 소득 · 고정비 · 목표 기간이 반영된 시나리오입니다.</p>
          </div>
          {demoState === "loading" && (
            <article className="start-card" aria-busy="true">
              <p className="eyebrow">데모 항로</p>
              <h2>데모 항로를 불러오고 있습니다</h2>
            </article>
          )}
          {demoState === "error" && (
            <article className="start-card" role="alert">
              <p className="eyebrow">데모 항로</p>
              <h2>데모 항로를 불러오지 못했습니다</h2>
              <p>직접 입력으로 계속 시작할 수 있습니다.</p>
            </article>
          )}
          {demoState === "ready" && demoTesters.length === 0 && (
            <article className="start-card">
              <p className="eyebrow">데모 항로</p>
              <h2>지금 선택할 수 있는 데모 항로가 없습니다.</h2>
              <p>직접 입력으로 첫 계획을 만들어 주세요.</p>
            </article>
          )}
          {demoTesters.map((tester) => (
            <article key={tester.testerId} className="start-card demo-card">
              <p className="eyebrow">데모 시나리오</p>
              <h2>{tester.displayName}</h2>
              <p>{tester.description}</p>
              <p className="demo-card-finance">
                월 소득 {formatMoneyCompact(tester.monthlyIncome)} · 고정비{" "}
                {formatMoneyCompact(tester.monthlyFixedCost)}
              </p>
              <p className="demo-card-goal">
                {tester.goalName} · {formatMoneyCompact(tester.goalTargetAmount)} · {tester.goalMonths}개월
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
          <article className="start-card direct-card">
            <p className="eyebrow">내 계획</p>
            <h2>내 정보로 시작하기</h2>
            <p>월 소득과 고정비, 최소 3개월 거래로 계산합니다.</p>
            <button className="primary" disabled={busy} onClick={() => setDirect(true)}>
              직접 시작하기
            </button>
          </article>
        </section>
      </main>
    );

  if (directStep === 4)
    return (
      <main className="onboarding-shell narrow onboarding-direct ui-demo-onboarding">
        <ProgressStepper current={1} />
        <header className="ui-demo-screen-header">
          <h1>미리 알고 있는 큰 지출이 있나요?</h1>
          <p>여행, 전자기기 구매, 등록금처럼 예정된 지출을 알려주면 계획에 미리 반영할 수 있어요.</p>
        </header>
        <div className="ui-demo-screen-body">
          {error && (
            <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
              {error}
            </p>
          )}
          <section className="planned-expenses-card" aria-label="등록한 예정지출">
            <div className="planned-expenses-heading">
              <div>
                <h2>등록한 예정지출</h2>
                <p>현재 {scheduled.length}건이 계획에 반영돼요.</p>
              </div>
            </div>

            {scheduleState === "loading" && (
              <div className="planned-expenses-empty" aria-busy="true">
                <span className="ui-demo-spinner" aria-hidden="true" />
                <p>예정지출을 불러오고 있습니다.</p>
              </div>
            )}
            {scheduleState === "error" && (
              <div className="planned-expenses-empty" role="alert">
                <p>예정지출을 불러오지 못했습니다. 이전 단계에서 다시 시도해 주세요.</p>
              </div>
            )}
            {scheduleState === "ready" && scheduled.length === 0 && !addingExpense && (
              <div className="planned-expenses-empty">
                <div className="planned-expenses-empty-icon">
                  <svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true">
                    <rect x="2" y="5" width="18" height="15" rx="3" stroke="#9CA3AF" strokeWidth="1.5" />
                    <path d="M2 10h18M7 2v3M15 2v3" stroke="#9CA3AF" strokeWidth="1.5" strokeLinecap="round" />
                  </svg>
                </div>
                <p>아직 예정된 큰 지출이 없어요.</p>
              </div>
            )}

            {scheduled.map((item) =>
              editingExpenseId === item.id ? (
                <form
                  key={item.id}
                  className="planned-expense-form"
                  onSubmit={(event) => void editScheduled(event, item)}
                >
                  <p className="planned-expense-form-title">예정 지출 수정</p>
                  <div className="planned-expense-form-grid">
                    <label>
                      항목명
                      <input name="editScheduledName" defaultValue={item.name} required maxLength={100} />
                    </label>
                    <label>
                      예정 날짜
                      <input name="editScheduledDate" type="date" defaultValue={item.scheduledDate} min={kstDate()} max={targetDateHint} required />
                    </label>
                    <label>
                      금액
                      <input name="editScheduledAmount" type="number" defaultValue={item.amount} min="1" step="1" required />
                    </label>
                  </div>
                  <div className="planned-expense-form-actions">
                    <button type="button" className="ui-demo-ghost-button" onClick={() => setEditingExpenseId(null)}>취소</button>
                    <button className="ui-demo-small-button" disabled={scheduleBusy}>수정 저장</button>
                  </div>
                </form>
              ) : (
                <div key={item.id} className="planned-expense-item">
                  <div className="planned-expense-icon">
                    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <rect x="1.5" y="3" width="13" height="11.5" rx="2.5" stroke="#4F58FF" strokeWidth="1.4" />
                      <path d="M1.5 7h13M5 1.5v3M11 1.5v3" stroke="#4F58FF" strokeWidth="1.4" strokeLinecap="round" />
                    </svg>
                  </div>
                  <div className="planned-expense-copy">
                    <strong>{item.name}</strong>
                    <span>{item.scheduledDate.replaceAll("-", ".")}</span>
                  </div>
                  <strong className="planned-expense-amount">{formatMoneyCompact(item.amount)}</strong>
                  <button type="button" className="planned-expense-action" onClick={() => setEditingExpenseId(item.id)} aria-label={`${item.name} 수정`} disabled={scheduleBusy}>
                    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M2 10.5V12h1.5l7.8-7.8-1.5-1.5L2 10.5zM8.8 3.7l1.5 1.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                  </button>
                  <button type="button" className="planned-expense-action delete" onClick={() => void cancelScheduled(item)} aria-label={`${item.name} 삭제`} disabled={scheduleBusy}>
                    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M2.5 3.5h9M5 1.75h4M4 5.25v5.5M7 5.25v5.5M10 5.25v5.5M3.5 3.5l.5 8.25h6l.5-8.25" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                  </button>
                </div>
              ),
            )}

            {addingExpense ? (
              <form className="planned-expense-form" onSubmit={(event) => void addScheduled(event)}>
                <p className="planned-expense-form-title">예정 지출 추가</p>
                <div className="planned-expense-form-grid">
                  <label>항목명<input name="scheduledName" placeholder="일본 여행" required maxLength={100} /></label>
                  <label>예정 날짜<input name="scheduledDate" type="date" required min={kstDate()} max={targetDateHint} /></label>
                  <label>금액<input name="scheduledAmount" type="number" min="1" step="1" required /></label>
                </div>
                <div className="planned-expense-form-actions">
                  <button type="button" className="ui-demo-ghost-button" onClick={() => setAddingExpense(false)}>취소</button>
                  <button className="ui-demo-small-button" disabled={scheduleBusy}>{scheduleBusy ? "추가 중" : "추가하기"}</button>
                </div>
              </form>
            ) : (
              <button type="button" className="planned-expense-add" onClick={() => setAddingExpense(true)}>
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M8 3v10M3 8h10" stroke="#4F58FF" strokeWidth="2" strokeLinecap="round" /></svg>
                예정지출 추가
              </button>
            )}

            {scheduled.length > 0 && (
              <div className="planned-expenses-total">
                <span>예정 지출 합계</span>
                <strong>{formatMoneyCompact(scheduled.reduce((total, item) => total + item.amount, 0))}</strong>
              </div>
            )}
          </section>

          <div className="planned-expenses-tip">
            <div aria-hidden="true"><svg width="10" height="10" viewBox="0 0 10 10" fill="none"><circle cx="5" cy="5" r="4" stroke="#4F58FF" strokeWidth="1.2" /><path d="M5 3.5v2.5M5 7.5v.5" stroke="#4F58FF" strokeWidth="1.2" strokeLinecap="round" /></svg></div>
            <p>예정지출이 없어도 괜찮아요. 나중에 대시보드에서 언제든지 추가할 수 있습니다.</p>
          </div>

          <div className="ui-demo-bottom-actions">
            <button type="button" className="ui-demo-prev" onClick={() => { setError(""); setDirectStep(3); }}>← 이전</button>
            <button type="button" className="primary ui-demo-next" onClick={() => { setError(""); setDirectStep(5); }}>
              마이데이터 연결
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3.5 8h9M9.5 4.5l3.5 3.5-3.5 3.5" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
            </button>
          </div>
        </div>
      </main>
    );

  if (directStep === 5)
    return (
      <main className="onboarding-shell narrow onboarding-direct ui-demo-onboarding">
        <ProgressStepper current={2} />
        <header className="ui-demo-screen-header">
          <h1>실제 소비내역을 연결해주세요</h1>
          <p>최근 소비 패턴과 변동성을 분석해 나에게 맞는 계획을 계산합니다.</p>
        </header>
        <div className="ui-demo-screen-body">
          {error && (
            <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
              {error}
            </p>
          )}

          {demoTransactions ? (
            <section className="mydata-complete-card" aria-live="polite">
              <div className="mydata-check-icon"><svg width="26" height="26" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8l3.5 3.5L13 5" stroke="#0DB97A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg></div>
              <div><h2>소비내역 연결 완료</h2><p>최근 {demoTransactions.completeMonths}개월 데이터 확인 완료</p></div>
              <div className="mydata-metrics">
                <div><span>분석된 거래</span><strong>{demoTransactions.inserted.toLocaleString("ko-KR")}건</strong></div>
                <div><span>적용 패턴</span><strong>{demoPatternLabels[demoTransactions.testerId]}</strong></div>
                <div><span>데이터 기간</span><strong>{demoTransactions.completeMonths}개월</strong></div>
              </div>
            </section>
          ) : (
            <section className="mydata-connect-card" aria-busy={busy}>
              <div className="mydata-card-icon">
                {busy ? <span className="ui-demo-spinner" aria-hidden="true" /> : <svg width="38" height="38" viewBox="0 0 38 38" fill="none" aria-hidden="true"><rect x="3" y="9" width="32" height="22" rx="4" stroke="#4F58FF" strokeWidth="2" /><path d="M3 16h32M8 23h8M8 27h5" stroke="#4F58FF" strokeWidth="1.8" strokeLinecap="round" /><circle cx="27" cy="25" r="6" fill="#EEF0FF" stroke="#4F58FF" strokeWidth="1.8" /><path d="M24.5 25l2 2 3.5-3.5" stroke="#4F58FF" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>}
              </div>
              <div className="mydata-connect-copy">
                <h2>마이데이터 연결</h2>
                <p>샘플 소비내역을 적용해 최근 24개월의 소비 패턴을 분석합니다.</p>
                <span><svg width="13" height="13" viewBox="0 0 13 13" fill="none" aria-hidden="true"><path d="M6.5 1.5L10.5 3.5v3c0 2.5-2 4.5-4 5.5-2-1-4-3-4-5.5v-3L6.5 1.5z" stroke="#8A93A3" strokeWidth="1.2" /></svg>Odyssey 계획 계산용 샘플 데이터</span>
              </div>
              {busy && <strong className="mydata-loading-copy">소비내역을 분석하고 있어요</strong>}
            </section>
          )}

          <section className="mydata-data-points">
            <h2>연결하면 확인하는 내용</h2>
            <div>
              {[
                ["📊", "월별 유동지출", "월마다 실제 사용한 생활비 추적"],
                ["📈", "소비 변동 패턴", "지출이 많은 달과 적은 달의 패턴 분석"],
                ["🗂", "카테고리별 소비", "식비, 교통, 쇼핑 등 분류된 지출 내역"],
                ["🔍", "예상 밖 지출 탐지", "갑작스러운 큰 지출 자동 감지"],
              ].map(([icon, label, description]) => (
                <article key={label}>
                  <span aria-hidden="true">{icon}</span>
                  <div><strong>{label}</strong><p>{description}</p></div>
                  {demoTransactions && <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8l3.5 3.5L13 5" stroke="#0DB97A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>}
                </article>
              ))}
            </div>
          </section>

          <div className="mydata-privacy-note">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M7 1.5L12 4v4c0 2.8-2.3 5-5 6.5C4.3 13 2 10.8 2 8V4L7 1.5z" stroke="#C0C7D0" strokeWidth="1.2" /></svg>
            <p>샘플 소비 데이터는 Odyssey 계획 계산에만 사용됩니다.</p>
          </div>

          <div className="ui-demo-bottom-actions">
            <button type="button" className="ui-demo-prev" disabled={busy} onClick={() => { setError(""); setDirectStep(4); }}>← 이전</button>
            <button type="button" className="primary ui-demo-next" disabled={busy} onClick={() => void applySample()}>
              {busy ? "계획 만드는 중" : "계획 만들기"}
              {!busy && <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3.5 8h9M9.5 4.5l3.5 3.5-3.5 3.5" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>}
            </button>
          </div>
        </div>
      </main>
    );

  return (
    <main className="onboarding-shell narrow onboarding-direct">
      <ProgressStepper current={0} />
      <header className="direct-header">
        <p className="eyebrow">출발 정보</p>
        <h1>이루고 싶은 목표부터 정해볼게요</h1>
        <p>목표와 현재 상황을 입력하면 첫 계획을 계산해드려요.</p>
      </header>
      <form className="onboarding-form" onSubmit={(event) => void submit(event)}>
        <div className="onboarding-form-card direct-layout-grid">
          <fieldset className="direct-basic-panel">
            <legend>나의 기준</legend>
            <div className="field-row">
              <label>
                생년월일
                <input name="birthDate" type="date" required value={birthDate} onChange={(event) => setBirthDate(event.target.value)} />
              </label>
              <label>시도<select aria-label="시도" value={selectedSido} onChange={(event) => { setSelectedSido(event.target.value); setRegionCode(""); }} required><option value="">시도 선택</option>{sidoRegions.map((item) => <option key={`${item.code}-${item.name}`} value={item.code}>{item.name}</option>)}</select></label>
              <label>시군구<select name="regionCode" aria-label="시군구" required disabled={!selectedSido} value={regionCode} onChange={(event) => setRegionCode(event.target.value)}><option value="">시군구 선택</option>{sigunguRegions.filter((item) => item.sidoCode === selectedSido).map((item) => <option key={`${item.code}-${item.name}`} value={item.code}>{item.name}</option>)}</select></label>
            </div>
          </fieldset>
          <fieldset className="direct-finance-panel">
            <legend>한 달의 생활</legend>
            <div className="field-row">
              <MoneyField
                label="월 소득"
                name="monthlyIncome"
                minimum={0}
                value={moneyHints.monthlyIncome ?? ""}
                onChange={(value) =>
                  setMoneyHints((current) => ({ ...current, monthlyIncome: value }))
                }
              />
              <MoneyField
                label="월 고정비"
                name="monthlyFixedCost"
                minimum={0}
                value={moneyHints.monthlyFixedCost ?? ""}
                onChange={(value) =>
                  setMoneyHints((current) => ({ ...current, monthlyFixedCost: value }))
                }
              />
            </div>
          </fieldset>
          <fieldset className="direct-goal-panel">
            <legend>금융 목표</legend>
            <label>
              목표 이름
              <input name="goalName" required minLength={1} maxLength={100} value={goalName} onChange={(event) => setGoalName(event.target.value)} />
            </label>
            <div className="field-row">
              <MoneyField
                label="목표 금액"
                name="targetAmount"
                minimum={1}
                value={moneyHints.targetAmount ?? ""}
                onChange={(value) =>
                  setMoneyHints((current) => ({ ...current, targetAmount: value }))
                }
              />
              <MoneyField
                label="현재 모은 금액"
                name="currentSavedAmount"
                minimum={0}
                defaultValue="0"
                value={moneyHints.currentSavedAmount ?? ""}
                onChange={(value) =>
                  setMoneyHints((current) => ({ ...current, currentSavedAmount: value }))
                }
              />
            </div>
            <label>
              목표 날짜
              <input
                name="targetDate"
                type="date"
                required
                value={targetDateHint}
                onChange={(event) => setTargetDateHint(event.target.value)}
              />
            </label>
            {remainingMonths(targetDateHint) !== null && (
              <p className="target-date-hint">
                목표까지 {remainingMonths(targetDateHint)}개월 남았어요
              </p>
            )}
          </fieldset>
        </div>
        {error && (
          <p ref={errorRef} tabIndex={-1} role="alert" className="notice danger">
            {error}
          </p>
        )}
        <div className="onboarding-form-action">
          <button className="primary full" disabled={busy}>
            {busy
              ? "계산하고 있습니다"
              : activeGoalId === null
                ? "계획 만들기"
                : "계획 다시 계산하기"}
          </button>
        </div>
      </form>
    </main>
  );
};
