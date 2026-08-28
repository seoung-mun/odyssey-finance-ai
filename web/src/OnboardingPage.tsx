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

const koreanWon = (value: string): string => {
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount < 10_000) return "";
  const man = amount / 10_000;
  if (man >= 10_000) {
    const eok = man / 10_000;
    return `${Number.isInteger(eok) ? eok : eok.toFixed(1)}억원`;
  }
  return `${Number.isInteger(man) ? man.toLocaleString("ko-KR") : Math.floor(man).toLocaleString("ko-KR")}만원`;
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

const planCopy = (nominalLevel: number | null) => {
  if (nominalLevel === 0.7)
    return ["소비 여유형", "매달 쓸 수 있는 금액을 가장 넉넉하게 잡았어요."];
  if (nominalLevel === 0.8) return ["균형형", "소비 여유와 계획 안정성을 함께 고려했어요."];
  return ["목표 우선형", "월 사용 금액을 낮춰 계획 안정성을 높였어요."];
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
  const [selectedOptionId, setSelectedOptionId] = useState<number | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [moneyHints, setMoneyHints] = useState<Record<string, string>>({});
  const [targetDateHint, setTargetDateHint] = useState("");

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

  const selectedOption = plan?.options.find((option) => option.id === selectedOptionId) ?? null;

  if (plan && selectedOption && confirming) {
    const [title] = planCopy(selectedOption.nominalLevel);
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
        <section className="confirmation-card" aria-label="선택한 계획 확인">
          <div className="confirmation-hero">
            <div>
              <p className="eyebrow">선택한 {title} 계획</p>
              <p>매달 쓸 수 있는 금액</p>
            </div>
            <strong>{won.format(selectedOption.recommendedMonthlySpending)}</strong>
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
              <dd>{percent.format(selectedOption.historicalFeasibilityRatio)}</dd>
            </div>
          </dl>
          {selectedOption.aggressiveWarning && (
            <p className="plan-card-warning warning-text">주의: 최근 소비보다 상당히 낮습니다.</p>
          )}
        </section>
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
      <main className="onboarding-shell">
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
          {plan.options
            .filter((option) => option.optionType === "PRESET")
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
                    <strong>{won.format(option.recommendedMonthlySpending)}</strong>
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
                ? `${planCopy(selectedOption.nominalLevel)[0]} · 월 ${won.format(selectedOption.recommendedMonthlySpending)}`
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
                월 소득 {won.format(tester.monthlyIncome)} · 고정비{" "}
                {won.format(tester.monthlyFixedCost)}
              </p>
              <p className="demo-card-goal">
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
          <fieldset className="direct-transactions-panel">
            <legend>최근 거래 · 서로 다른 3개월</legend>
            {[0, 1, 2].map((index) => (
              <div className="transaction-row" key={index}>
                <label>
                  거래일 {index + 1}
                  <input name={`transactionDate${index}`} type="date" required />
                </label>
                <label>
                  거래 금액 {index + 1}
                  <input
                    name={`transactionAmount${index}`}
                    type="number"
                    min="1"
                    step="1"
                    required
                  />
                </label>
                <label>
                  거래 분류 {index + 1}
                  <input name={`transactionCategory${index}`} required />
                </label>
              </div>
            ))}
          </fieldset>
          <fieldset className="direct-goal-panel">
            <legend>금융 목표</legend>
            <label>
              목표 이름
              <input name="goalName" required minLength={1} maxLength={100} />
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
