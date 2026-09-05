import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";
import { DashboardDialogs } from "./DashboardDialogs";
import { formatMoneyCompact } from "./formatMoney";
import { PlanEditor, ReplanComparison } from "./PlanWorkflow";
import {
  parseDashboard,
  parseExplanation,
  parseImport,
  parseObject,
  parsePlanVersion,
  parseReplanEvents,
  parseScheduledExpenses,
  type Dashboard,
  type PercentileBand,
  type PlanVersion,
  type ScheduledExpense,
} from "./types";

const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });

const planName = (nominalLevel: number | null, optionType: "PRESET" | "CUSTOM") => {
  if (optionType === "CUSTOM" || nominalLevel === null) return "맞춤 계획";
  if (nominalLevel === 0.7) return "소비 여유형";
  if (nominalLevel === 0.8) return "균형형";
  return "목표 우선형";
};

const chartWon = formatMoneyCompact;

type DashboardNotice = "OVERSPEND" | "MONTHLY_REPLAN" | null;

type ReplanRequestResult =
  | { ok: true }
  | { ok: false; error: ApiError };

const localDateKey = (date: Date) => [
  date.getFullYear(),
  String(date.getMonth() + 1).padStart(2, "0"),
  String(date.getDate()).padStart(2, "0"),
].join("-");

const elapsedMonthIndex = (asOfDate: string | undefined, lastMonth: number) => {
  if (!asOfDate) return 0;
  const [year, month] = asOfDate.split("-").map(Number);
  if (!Number.isInteger(year) || !Number.isInteger(month)) return 0;
  const now = new Date();
  return Math.min(
    lastMonth,
    Math.max(0, (now.getFullYear() - year) * 12 + now.getMonth() + 1 - month),
  );
};

type RoutePoint = { monthIndex: number; p10: number; p50: number; p90: number };

const RouteChart = ({
  bands,
  startSaved,
  currentSaved,
  targetAmount,
  asOfDate,
}: {
  bands: PercentileBand[];
  startSaved: number;
  currentSaved: number;
  targetAmount: number;
  asOfDate?: string;
}) => {
  if (!bands.length) {
    return <div className="route-empty"><h2>항로 현황</h2><p>계획 경로 데이터가 아직 없습니다.</p></div>;
  }

  const points: RoutePoint[] = [
    { monthIndex: 0, p10: startSaved, p50: startSaved, p90: startSaved },
    ...bands.map((item) => ({
      monthIndex: item.monthIndex,
      p10: startSaved + item.p10,
      p50: startSaved + item.p50,
      p90: startSaved + item.p90,
    })),
  ];
  const lastMonth = points[points.length - 1]?.monthIndex ?? 1;
  const currentMonth = elapsedMonthIndex(asOfDate, lastMonth);
  const plannedNow = points.find((point) => point.monthIndex >= currentMonth) ?? points[points.length - 1];
  const difference = currentSaved - plannedNow.p50;
  const ahead = difference >= 0;
  const values = points.flatMap((point) => [point.p10, point.p90]);
  values.push(currentSaved, targetAmount);
  const rawMin = Math.min(...values);
  const rawMax = Math.max(...values);
  const padding = Math.max((rawMax - rawMin) * 0.12, 1);
  const min = Math.max(0, rawMin - padding);
  const max = rawMax + padding;
  const width = 760;
  const height = 350;
  const frame = { left: 80, right: 38, top: 48, bottom: 52 };
  const plotWidth = width - frame.left - frame.right;
  const plotHeight = height - frame.top - frame.bottom;
  const coordinate = (monthIndex: number, value: number) => [
    frame.left + (monthIndex / Math.max(lastMonth, 1)) * plotWidth,
    frame.top + (1 - (value - min) / Math.max(max - min, 1)) * plotHeight,
  ] as const;
  const line = (key: "p10" | "p50" | "p90") =>
    points.map((point) => coordinate(point.monthIndex, point[key]).join(",")).join(" ");
  const band = [...line("p90").split(" "), ...line("p10").split(" ").reverse()].join(" ");
  const [markerX, markerY] = coordinate(currentMonth, currentSaved);
  const [, plannedY] = coordinate(currentMonth, plannedNow.p50);
  const yTicks = Array.from({ length: 4 }, (_, index) => min + ((max - min) * index) / 3).reverse();
  const xIndexes = [...new Set([0, Math.round(lastMonth / 3), Math.round((lastMonth * 2) / 3), lastMonth])];

  return (
    <div className="route-chart-card">
      <div className="route-status-heading">
        <div>
          <h2>항로 현황</h2>
          <p className={ahead ? "route-status ahead" : "route-status behind"}>
            <span aria-hidden="true" />{ahead ? "계획보다 앞서가고 있어요" : "계획보다 조금 늦어지고 있어요"}
          </p>
        </div>
        <div className="route-difference">
          <span>기준 경로 대비</span>
          <strong className={ahead ? "ahead" : "behind"}>{ahead ? "+" : "-"}{chartWon(Math.abs(difference))}</strong>
        </div>
      </div>
      <div className="route-legend-list" aria-label="차트 범례">
        <span className="route-key planned"><i />계획 기준 경로</span>
        <span className="route-key current"><i />현재 위치</span>
        <span className="route-key range"><i />정상 변동 범위</span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="계획 저축 경로와 현재 저축 위치">
        <defs>
          <linearGradient id="dashboard-route-range" x1="0" y1="0" x2="0" y2="1">
            <stop stopColor="#6eaed2" stopOpacity=".28" />
            <stop offset="1" stopColor="#bfe4f1" stopOpacity=".12" />
          </linearGradient>
          <filter id="dashboard-marker-shadow" x="-80%" y="-80%" width="260%" height="260%">
            <feDropShadow dx="0" dy="4" stdDeviation="5" floodColor="#14284a" floodOpacity=".22" />
          </filter>
        </defs>
        <rect x={frame.left} y={frame.top} width={plotWidth} height={plotHeight} rx="14" className="route-chart-background" />
        {yTicks.map((value, index) => {
          const y = frame.top + (index / 3) * plotHeight;
          return (
            <g key={index}>
              <line x1={frame.left} y1={y} x2={frame.left + plotWidth} y2={y} className="route-grid-line" />
              <text x={frame.left - 10} y={y + 4} textAnchor="end" className="route-axis-label">{chartWon(value)}</text>
            </g>
          );
        })}
        <polygon points={band} fill="url(#dashboard-route-range)" />
        <polyline points={line("p10")} className="route-band-line" />
        <polyline points={line("p90")} className="route-band-line" />
        <polyline points={line("p50")} className="route-plan-line" />
        <line x1={markerX} y1={plannedY} x2={markerX} y2={markerY} className="route-gap-line" />
        <g transform={`translate(${markerX} ${markerY})`} filter="url(#dashboard-marker-shadow)">
          <circle r="9" className={ahead ? "route-current-dot ahead" : "route-current-dot behind"} />
          <path d="M-16 9 Q-4 15 16 9 L10 17 Q-2 21 -13 16Z" className="route-boat-hull" />
          <line x1="1" y1="-20" x2="1" y2="10" className="route-boat-mast" />
          <path d="M1 -18 L14 7 L1 7Z" className="route-boat-sail" />
        </g>
        <g transform={`translate(${Math.min(frame.left + plotWidth - 47, Math.max(frame.left + 47, markerX))} ${Math.max(frame.top + 18, markerY - 48)})`}>
          <rect x="-47" y="-14" width="94" height="28" rx="8" className="route-marker-label" />
          <text textAnchor="middle" y="4" className="route-marker-text">현재 {chartWon(currentSaved)}</text>
        </g>
        {xIndexes.map((monthIndex) => {
          const [x] = coordinate(monthIndex, min);
          return <text key={monthIndex} x={x} y={height - 18} textAnchor="middle" className="route-axis-label">{monthIndex === 0 ? "시작" : monthIndex === lastMonth ? "목표" : `+${monthIndex}개월`}</text>;
        })}
      </svg>
    </div>
  );
};

const DemoReplanNotice = ({
  notice,
  currentMonthlySpending,
  busy,
  onConfirm,
}: {
  notice: Exclude<DashboardNotice, null>;
  currentMonthlySpending: number | null;
  busy: boolean;
  onConfirm: () => void;
}) => {
  const month = new Date().getMonth() + 1;
  const overspend = notice === "OVERSPEND";
  return (
    <section className={`dashboard-demo-notice ${overspend ? "warning" : "monthly"}`} role={overspend ? "alert" : "status"}>
      <div className="dashboard-demo-notice-copy">
        <span className="dashboard-demo-notice-icon" aria-hidden="true">{overspend ? "!" : "📅"}</span>
        <div>
          <strong>{overspend ? "이번 달 소비 속도가 계획보다 빨라요" : `${month}월 정기 재계획을 확인할 시간이에요`}</strong>
          <p>{overspend
            ? "신형 노트북 구매 · 300만원이 반영되었습니다. 현재 상황을 반영해 계획을 다시 확인해보세요."
            : "지난달 소비와 현재 목표 상황을 반영해 이번 달 계획을 다시 계산해보세요."}</p>
        </div>
      </div>
      <div className="dashboard-demo-notice-actions">
        <div><span>현재 계획</span><strong>{currentMonthlySpending === null ? "선택 전" : `월 ${formatMoneyCompact(currentMonthlySpending)}`}</strong></div>
        <button type="button" className="primary" disabled={busy} onClick={onConfirm}>{busy ? "계산 중…" : "새 계획 확인하기"}</button>
      </div>
    </section>
  );
};

export const DashboardPage = ({ api }: { api: Pick<ApiClient, "get" | "post"> & Partial<Pick<ApiClient, "put" | "patch">> }) => {
  const navigate = useNavigate();
  const [data, setData] = useState<Dashboard | null>(null);
  const [scheduledExpenses, setScheduledExpenses] = useState<ScheduledExpense[]>([]);
  const [scheduleState, setScheduleState] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<ApiError | null>(null);
  const [refreshed, setRefreshed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [pollTimedOut, setPollTimedOut] = useState(false);
  const [proposal, setProposal] = useState<PlanVersion | null>(null);
  const [proposalEventId, setProposalEventId] = useState<number | null>(null);
  const [proposalAccepted, setProposalAccepted] = useState(false);
  const [view, setView] = useState<"dashboard" | "edit" | "replan">("dashboard");
  const [replanning, setReplanning] = useState(false);
  const [replanError, setReplanError] = useState("");
  const [dashboardNotice, setDashboardNotice] = useState<DashboardNotice>(null);
  const [demoBusy, setDemoBusy] = useState(false);
  const replanBusy = useRef(false);
  const demoBusyRef = useRef(false);
  const loadSequence = useRef(0);
  const errorRef = useRef<HTMLElement>(null);

  const load = useCallback(async (retryConflict = true) => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    setError(null);
    setPollTimedOut(false);
    setScheduleState("loading");
    const scheduledRequest = (async () => {
      try {
        const expenses = parseScheduledExpenses(await api.get("/scheduled-expenses?status=PLANNED", parseScheduledExpenses));
        if (sequence === loadSequence.current) {
          setScheduledExpenses(expenses.filter((expense) => expense.status === "PLANNED"));
          setScheduleState("ready");
        }
      } catch {
        if (sequence === loadSequence.current) setScheduleState("error");
      }
    })();
    try {
      const dashboard = parseDashboard(await api.get("/dashboard", parseDashboard));
      if (sequence === loadSequence.current) setData(dashboard);
    } catch (reason) {
      let apiError = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      if (apiError.status === 409 && retryConflict) {
        try {
          const dashboard = parseDashboard(await api.get("/dashboard", parseDashboard));
          if (sequence === loadSequence.current) {
            setData(dashboard);
            setRefreshed(true);
          }
          return;
        } catch (retryReason) {
          apiError = retryReason instanceof ApiError ? retryReason : new ApiError(0, "NETWORK_ERROR");
        }
      }
      if (apiError.status === 403) navigate("/forbidden", { replace: true });
      else if (apiError.status === 404) navigate("/not-found", { replace: true });
      else if (sequence === loadSequence.current) setError(apiError);
    } finally {
      await scheduledRequest;
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [api, navigate]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);

  const planId = data?.activePlan?.id;
  const explanationStatus = data?.activePlan?.explanation.status;
  const shouldPollExplanation = explanationStatus === "PENDING" || explanationStatus === "PROCESSING";
  useEffect(() => {
    if (!planId || !shouldPollExplanation) return;
    let cancelled = false;
    let attempts = 0;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      attempts += 1;
      try {
        const explanation = parseExplanation(await api.get(`/plan-versions/${planId}/explanation`, parseExplanation));
        if (cancelled) return;
        setData((current) => current?.activePlan ? { ...current, activePlan: { ...current.activePlan, explanation } } : current);
        if (["READY", "FALLBACK", "FAILED"].includes(explanation.status)) return;
      } catch {
        if (cancelled) return;
      }
      if (attempts < 15) timer = setTimeout(poll, 2000);
      else if (!cancelled) setPollTimedOut(true);
    };
    timer = setTimeout(poll, 2000);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [api, planId, shouldPollExplanation]);

  const requestReplan = async (): Promise<ReplanRequestResult> => {
    if (!data?.goal || replanBusy.current) {
      return { ok: false, error: new ApiError(409, "REPLAN_IN_PROGRESS", "재계획 요청이 이미 진행 중입니다.") };
    }
    replanBusy.current = true;
    setReplanning(true);
    setReplanError("");
    try {
      const response = parseObject(await api.post(`/goals/${data.goal.id}/replan`, undefined, parseObject));
      const eventId = response.replanEventId;
      if (!Number.isSafeInteger(eventId) || Number(eventId) < 1) throw new Error("INVALID_RESPONSE");
      setProposal(parsePlanVersion(response));
      setProposalEventId(Number(eventId));
      setProposalAccepted(false);
      setView("replan");
      return { ok: true };
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      if (apiError.status === 409) await load(false);
      else if (apiError.status === 403) navigate("/forbidden", { replace: true });
      else if (apiError.status === 404) navigate("/not-found", { replace: true });
      else if (apiError.code === "PLAN_INFEASIBLE") setReplanError("목표 달성이 불가능한 계획입니다");
      else if (apiError.status === 422) setReplanError("현재 조건으로는 재계획할 수 없습니다. 목표 금액이나 날짜를 조정해 주세요.");
      else setReplanError(`${apiError.requestId ? `요청 ID ${apiError.requestId}. ` : ""}재계획하지 못했습니다. 잠시 후 다시 시도해 주세요.`);
      return { ok: false, error: apiError };
    } finally {
      replanBusy.current = false;
      setReplanning(false);
    }
  };

  const completeEdit = async (needsReplan: boolean) => {
    if (needsReplan) {
      const result = await requestReplan();
      if (!result.ok) throw result.error;
      return;
    }
    await load(false);
    setView("dashboard");
  };

  const addOverspendTransaction = async () => {
    if (demoBusyRef.current) return;
    demoBusyRef.current = true;
    setDemoBusy(true);
    setReplanError("");
    try {
      const now = new Date();
      parseImport(await api.post("/transactions/import", {
        transactions: [{
          transactionAt: now.toISOString(),
          amount: 3_000_000,
          transactionType: "PAYMENT",
          category: "쇼핑",
          merchantName: "신형 노트북 구매",
          sourceId: "MANUAL",
          externalTransactionId: `odyssey-demo-overspend-${localDateKey(now)}`,
        }],
      }, parseImport));
      await load(false);
      setDashboardNotice("OVERSPEND");
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      setReplanError(apiError.message || "과소비 거래를 반영하지 못했습니다.");
    } finally {
      demoBusyRef.current = false;
      setDemoBusy(false);
    }
  };

  const keepCurrentPlan = async () => {
    if (!proposalEventId || replanBusy.current) return;
    replanBusy.current = true;
    setReplanning(true);
    setReplanError("");
    try {
      await api.post(`/replan-events/${proposalEventId}/decision`, { decision: "KEEP_CURRENT_PLAN" }, parseObject);
      setProposal(null);
      setProposalEventId(null);
      setProposalAccepted(false);
      await load(false);
      setView("dashboard");
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      setReplanError(apiError.message || "기존 계획 유지 결정을 저장하지 못했습니다.");
    } finally {
      replanBusy.current = false;
      setReplanning(false);
    }
  };

  const selectProposal = async (optionId: number) => {
    if (!proposal || !proposalEventId || replanBusy.current) return;
    replanBusy.current = true;
    setReplanning(true);
    setError(null);
    try {
      if (!proposalAccepted) {
        try {
          await api.post(`/replan-events/${proposalEventId}/decision`, { decision: "ACCEPT_NEW_PLAN" }, parseObject);
        } catch (reason) {
          if (!(reason instanceof ApiError) || reason.status !== 409 || !data?.goal) throw reason;
          const events = parseReplanEvents(await api.get(`/goals/${data.goal.id}/replan-events`, parseReplanEvents));
          const confirmed = events.some((event) => event.id === proposalEventId && event.userDecision === "ACCEPT_NEW_PLAN" && event.proposedPlanVersionId === proposal.id);
          if (!confirmed) throw reason;
        }
        setProposalAccepted(true);
      }
      await api.post(`/plan-versions/${proposal.id}/select-option`, { planOptionId: optionId }, parsePlanVersion);
      setProposal(null);
      setProposalEventId(null);
      setProposalAccepted(false);
      await load(false);
      setView("dashboard");
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      setReplanError(apiError.message || "새 계획을 적용하지 못했습니다.");
    } finally {
      replanBusy.current = false;
      setReplanning(false);
    }
  };

  if (loading) return <main className="center-page" aria-busy="true"><p className="eyebrow">항로 확인 중</p><h1>최신 계획을 불러오고 있습니다</h1></main>;
  if (error) return (
    <main ref={errorRef} tabIndex={-1} className="center-page error-page">
      <p className="eyebrow">{error.status === 0 ? "네트워크 단절" : "서버 오류"}</p>
      <h1>{error.status === 0 ? "연결을 확인해 주세요" : "잠시 후 다시 확인해 주세요"}</h1>
      {error.requestId && <p>요청 ID: <code>{error.requestId}</code></p>}
      <button className="primary" onClick={() => void load(false)}>다시 불러오기</button>
    </main>
  );
  if (!data?.goal) return (
    <main className="center-page"><p className="eyebrow">첫 번째 목적지</p><h1>아직 목표가 없습니다</h1><p>원하는 금액과 날짜를 정하면 첫 계획을 만듭니다.</p><Link className="primary link-button" to="/onboarding">목표 만들기</Link></main>
  );

  const { goal, activePlan, selectedOption, monthProgress } = data;
  const progressRatio = Math.min(1, Math.max(0, goal.currentSavedAmount / goal.targetAmount));
  const monthlyRemaining = monthProgress ? Math.max(monthProgress.plannedMonthlySpending - monthProgress.actualToDate, 0) : null;
  const monthlyUsedRatio = monthProgress?.plannedMonthlySpending ? Math.min(1, Math.max(0, monthProgress.actualToDate / monthProgress.plannedMonthlySpending)) : 0;
  const selectedPlanName = selectedOption ? planName(selectedOption.nominalLevel, selectedOption.optionType) : "선택 전";
  const explanation = activePlan?.explanation.text;

  if (view === "edit") {
    return <PlanEditor api={api} goalId={goal.id} onCancel={() => setView("dashboard")} onSaved={completeEdit} />;
  }
  if (view === "replan" && proposal) {
    return <ReplanComparison dashboard={data} proposal={proposal} busy={replanning} error={replanError} onKeep={keepCurrentPlan} onApply={selectProposal} />;
  }

  return (
    <main className="dashboard-shell ui-demo-dashboard">
      {refreshed && <p role="status" className="notice">최신 상태를 불러왔습니다.</p>}
      {dashboardNotice && <DemoReplanNotice
        notice={dashboardNotice}
        currentMonthlySpending={selectedOption?.recommendedMonthlySpending ?? null}
        busy={replanning}
        onConfirm={() => void requestReplan()}
      />}
      {data.pendingProposal && <section className="notice proposal"><strong>새 계획이 도착했습니다.</strong><span>현실의 변화를 반영한 경로를 확인해 주세요.</span></section>}
      {activePlan?.status === "INFEASIBLE" && (
        <section className="notice danger"><strong>현재 조건으로는 목표에 닿기 어렵습니다.</strong><span>{activePlan.infeasibleReason}</span><div className="inline-actions"><Link to="/onboarding?step=goal">목표 줄이기</Link><Link to="/onboarding?step=goal">기간 늘리기</Link><Link to="/onboarding">입력 확인하기</Link></div></section>
      )}
      <div className="dashboard-grid">
        <div className="dashboard-main-column">
          <section className="dashboard-card goal-summary-card" aria-label="목표 진행 현황">
            <div className="goal-summary-heading">
              <div><p className="dashboard-label">{goal.name} 목표</p><div className="goal-amounts"><strong>{formatMoneyCompact(goal.currentSavedAmount)}</strong><span>/</span><em>{formatMoneyCompact(goal.targetAmount)}</em></div></div>
              <div className="goal-months-left"><span>목표일까지</span><strong>{goal.remainingMonths}<small>개월</small></strong></div>
            </div>
            <div className="dashboard-progress" role="progressbar" aria-label="목표 진행률" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progressRatio * 100)}><span style={{ width: `${progressRatio * 100}%` }} /></div>
            <p className="goal-progress-copy">목표의 {percent.format(progressRatio)}를 모았어요</p>
          </section>
          <section className="dashboard-card monthly-budget-card" aria-label="이번 달 소비 현황">
            <div className="monthly-budget-heading">
              <div><p className="dashboard-label">이번 달 더 쓸 수 있는 금액</p><strong className="monthly-budget-remaining">{monthlyRemaining === null ? "자료 없음" : formatMoneyCompact(monthlyRemaining)}</strong><p>이번 달 말까지 권장하는 남은 생활비예요.</p></div>
              <dl><div><dt>이번 달 한도</dt><dd>{monthProgress ? formatMoneyCompact(monthProgress.plannedMonthlySpending) : "자료 없음"}</dd></div><div><dt>현재 사용</dt><dd>{monthProgress ? formatMoneyCompact(monthProgress.actualToDate) : "자료 없음"}</dd></div></dl>
            </div>
            <div className="dashboard-progress monthly" role="progressbar" aria-label="이번 달 한도 사용률" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(monthlyUsedRatio * 100)}><span style={{ width: `${monthlyUsedRatio * 100}%` }} /></div>
            {monthProgress && <p className="monthly-progress-copy">한도의 {percent.format(monthlyUsedRatio)}를 사용했어요</p>}
          </section>
          <section className="dashboard-card dashboard-route-panel" aria-label="목표까지의 항로">
            {selectedOption ? <RouteChart bands={selectedOption.percentileBands} startSaved={activePlan?.snapshot?.currentSaved ?? goal.currentSavedAmount} currentSaved={goal.currentSavedAmount} targetAmount={goal.targetAmount} asOfDate={activePlan?.asOfDate} /> : <div className="route-empty"><h2>선택한 계획이 없습니다</h2><p>제안된 계획에서 유지할 수 있는 월 지출을 선택해 주세요.</p></div>}
          </section>
        </div>
        <aside className="dashboard-sidebar" aria-label="현재 계획과 예정 지출">
          <section className="dashboard-card current-plan-card">
            <div className="current-plan-heading"><h2>현재 계획 · {selectedPlanName}</h2><span>{activePlan?.status === "ACTIVE" ? "진행 중" : "확인 필요"}</span></div>
            {selectedOption ? <>
              <div className="plan-stability"><p>계획 안정성</p><div><strong>{percent.format(selectedOption.simulationCoverage)}</strong><span><i style={{ width: `${selectedOption.simulationCoverage * 100}%` }} /></span></div></div>
              <dl className="current-plan-details"><div><dt>목표일</dt><dd>{goal.targetDate}</dd></div><div><dt>남은 기간</dt><dd>{goal.remainingMonths}개월</dd></div><div><dt>월 유동지출 한도</dt><dd>{formatMoneyCompact(selectedOption.recommendedMonthlySpending)}</dd></div></dl>
              {explanation && <p className="current-plan-note">{explanation}</p>}
              {activePlan?.explanation.status === "PENDING" || activePlan?.explanation.status === "PROCESSING" ? <p className="current-plan-note" aria-live="polite">계획 설명을 정리하고 있습니다.</p> : null}
              {pollTimedOut && <p className="current-plan-note warning">설명 없이도 계산된 계획을 확인할 수 있습니다.</p>}
              {selectedOption.aggressiveWarning && <p role="alert" className="compact-warning">이 계획은 최근 소비패턴보다 상당히 낮은 수준입니다.</p>}
              {selectedOption.targetCoverageMet === false && <p role="alert" className="compact-warning">현재 소비 한도는 목표 안정성 수준에 미치지 못합니다.</p>}
            </> : <p className="current-plan-note">아직 선택한 계획이 없습니다.</p>}
          </section>
          <div className="dashboard-action-stack">
            <DashboardDialogs api={api} goalId={goal.id} currentPlanVersionId={activePlan?.id ?? null} onChanged={() => void load(false)} onEditPlan={() => setView("edit")} />
            <button className="secondary replan-launcher" disabled={replanning} onClick={() => void requestReplan()}>현재 시점 기준으로 다시 계산</button>
            <div className="dashboard-demo-actions" aria-label="데모 이벤트">
              <span>데모 이벤트</span>
              <button type="button" disabled={demoBusy || replanning} onClick={() => void addOverspendTransaction()}>{demoBusy ? "반영 중…" : "과소비 발생"}</button>
              <button type="button" disabled={demoBusy || replanning} onClick={() => setDashboardNotice("MONTHLY_REPLAN")}>월 1회 정기 재계획</button>
            </div>
          </div>
          <section className="dashboard-card scheduled-expenses-card">
            <h2>예정 지출</h2>
            {scheduleState === "loading" && <p role="status" aria-busy="true">예정 지출을 불러오고 있습니다.</p>}
            {scheduleState === "error" && <p role="alert">예정 지출을 불러오지 못했습니다.</p>}
            {scheduleState === "ready" && scheduledExpenses.length === 0 && <p>등록된 예정 지출이 없습니다.</p>}
            {scheduleState === "ready" && scheduledExpenses.length > 0 && <ul>{scheduledExpenses.map((expense) => <li key={expense.id}><div><strong>{expense.name}</strong><time dateTime={expense.scheduledDate}>{expense.scheduledDate.replace(/-/g, ".")}</time></div><span>{formatMoneyCompact(expense.amount)}</span></li>)}</ul>}
            <p className="scheduled-expenses-note">예정 지출은 계획 경로에 반영되어 있습니다.</p>
          </section>
        </aside>
      </div>
      {replanError && <p role="alert" className="notice danger">{replanError}</p>}
      {proposal && <section className="plan-options" aria-label="새 계획 선택지">{proposal.options.filter((option) => option.optionType === "PRESET").map((option) => <article key={option.id}><p className="eyebrow">{planName(option.nominalLevel, option.optionType)}</p><h2>{formatMoneyCompact(option.recommendedMonthlySpending)}</h2><p>계획 안정성 {percent.format(option.simulationCoverage)}</p><button className="primary" disabled={replanning} onClick={() => void selectProposal(option.id)}>이 계획 선택</button></article>)}</section>}
    </main>
  );
};
