import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, type ApiClient } from "./api";
import {
  parseExplanation,
  parseGoals,
  parseMe,
  parseObject,
  parsePlanOption,
  parsePlanVersion,
  parsePlanVersionSummaries,
  parseReplanEvents,
  type Goal,
  type PlanVersion,
  type PlanVersionSummary,
  type ReplanEvent,
} from "./types";

const won = new Intl.NumberFormat("ko-KR", { style: "currency", currency: "KRW", maximumFractionDigits: 0 });
const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });

type PageData = {
  goal: Goal;
  summaries: PlanVersionSummary[];
  plan: PlanVersion | null;
  events: ReplanEvent[];
};

const errorText = (error: ApiError) =>
  error.status === 503 ? "계산 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요." : error.message;

export const PlansPage = ({ api }: { api: Pick<ApiClient, "get" | "post"> }) => {
  const [data, setData] = useState<PageData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [customSpending, setCustomSpending] = useState("");
  const [requestedProposal, setRequestedProposal] = useState<{ planId: number; eventId: number } | null>(null);
  const busyRef = useRef(false);
  const sequenceRef = useRef(0);

  const load = useCallback(async (retryConflict = true) => {
    const sequence = ++sequenceRef.current;
    setLoading(true);
    setError("");
    try {
      const me = parseMe(await api.get("/me", parseMe));
      const goalId = me.activeGoalId;
      if (!Number.isSafeInteger(goalId)) throw new Error("ACTIVE_GOAL_REQUIRED");
      const goals = parseGoals(await api.get("/goals", parseGoals));
      const goal = goals.find((entry) => entry.id === goalId);
      if (!goal) throw new Error("ACTIVE_GOAL_NOT_FOUND");
      const summaries = parsePlanVersionSummaries(
        await api.get(`/goals/${goal.id}/plan-versions`, parsePlanVersionSummaries),
      );
      const events = parseReplanEvents(
        await api.get(`/goals/${goal.id}/replan-events`, parseReplanEvents),
      );
      if (summaries.length === 0) {
        if (sequence === sequenceRef.current) setData({ goal, summaries, plan: null, events });
        return;
      }
      const plan = parsePlanVersion(await api.get(`/plan-versions/${summaries[0].id}`, parsePlanVersion));
      if (sequence === sequenceRef.current) {
        setData({ goal, summaries, plan, events });
        setLoading(false);
      }
      void api.get(`/plan-versions/${plan.id}/explanation`, parseExplanation).then((value) => {
        const explanation = parseExplanation(value);
        if (sequence === sequenceRef.current)
          setData((current) => current ? { ...current, plan: { ...plan, explanation } } : current);
      }).catch(() => undefined);
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : null;
      if (apiError?.status === 409 && retryConflict) return load(false);
      if (sequence === sequenceRef.current) setError(apiError ? errorText(apiError) : "계획을 불러오지 못했습니다.");
    } finally {
      if (sequence === sequenceRef.current) setLoading(false);
    }
  }, [api]);

  const showVersion = async (planVersionId: number) => {
    const sequence = ++sequenceRef.current;
    setLoading(true);
    setError("");
    try {
      const plan = parsePlanVersion(
        await api.get(`/plan-versions/${planVersionId}`, parsePlanVersion),
      );
      if (sequence === sequenceRef.current) {
        setData((current) => current ? { ...current, plan } : current);
        setLoading(false);
      }
      void api.get(`/plan-versions/${planVersionId}/explanation`, parseExplanation).then((value) => {
        const explanation = parseExplanation(value);
        if (sequence === sequenceRef.current)
          setData((current) => current ? { ...current, plan: { ...plan, explanation } } : current);
      }).catch(() => undefined);
    } catch (reason) {
      if (sequence === sequenceRef.current)
        setError(reason instanceof ApiError ? errorText(reason) : "계획 상세를 불러오지 못했습니다.");
    } finally {
      if (sequence === sequenceRef.current) setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [load]);

  const mutate = async (action: () => Promise<void>, failure: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setError("");
    try {
      await action();
    } catch (reason) {
      setError(reason instanceof ApiError ? `${failure} ${errorText(reason)}` : failure);
    } finally {
      busyRef.current = false;
    }
  };

  const eventForPlan = (planId: number) =>
    data?.events.find((event) => event.proposedPlanVersionId === planId) ?? null;

  const selectOption = async (optionId: number) => {
    if (!data?.plan) return;
    await mutate(async () => {
      const event = eventForPlan(data.plan.id);
      const eventId = event?.id ?? (requestedProposal?.planId === data.plan.id ? requestedProposal.eventId : null);
      if (eventId && event?.userDecision !== "ACCEPT_NEW_PLAN") {
        await api.post(
          `/replan-events/${eventId}/decision`,
          { decision: "ACCEPT_NEW_PLAN" },
          parseObject,
        );
      }
      await api.post(
        `/plan-versions/${data.plan.id}/select-option`,
        { planOptionId: optionId },
        parsePlanVersion,
      );
      await load();
    }, "새 계획 수락을 완료하지 못했습니다.");
  };

  const createCustom = async () => {
    if (!data?.plan || !/^\d+$/.test(customSpending) || Number(customSpending) < 0) return;
    await mutate(async () => {
      const option = parsePlanOption(
        await api.post(
          `/plan-versions/${data.plan.id}/custom-option`,
          { monthlySpending: Number(customSpending) },
          parsePlanOption,
        ),
      );
      setData((current) =>
        current ? { ...current, plan: { ...current.plan, options: [...current.plan.options, option] } } : current,
      );
      setCustomSpending("");
    }, "나만의 소비 한도를 만들지 못했습니다.");
  };

  const decide = async (eventId: number, decision: "KEEP_CURRENT_PLAN" | "ACCEPT_NEW_PLAN") => {
    await mutate(async () => {
      await api.post(`/replan-events/${eventId}/decision`, { decision }, parseObject);
      setRequestedProposal(null);
      await load();
    }, "재계획 결정을 저장하지 못했습니다.");
  };

  const retry = async (event: ReplanEvent) => {
    await mutate(async () => {
      await api.post(`/replan-events/${event.id}/retry`, undefined, parsePlanVersion);
      await load();
    }, "재계획을 다시 계산하지 못했습니다.");
  };

  const requestReplan = async () => {
    if (!data) return;
    const sequence = sequenceRef.current;
    await mutate(async () => {
      const response = parseObject(
        await api.post(`/goals/${data.goal.id}/replan`, undefined, parseObject),
      );
      const plan = parsePlanVersion(response);
      const eventId = response.replanEventId;
      if (!Number.isSafeInteger(eventId) || Number(eventId) < 1) throw new Error("INVALID_RESPONSE");
      if (sequence !== sequenceRef.current) return;
      setRequestedProposal({ planId: plan.id, eventId: Number(eventId) });
      setData((current) => current ? {
        ...current,
        summaries: [
          {
            id: plan.id,
            versionNo: plan.versionNo,
            generationType: plan.generationType,
            status: plan.status,
            asOfDate: plan.asOfDate,
            createdAt: plan.createdAt,
            activatedAt: plan.activatedAt,
            infeasibleReason: plan.infeasibleReason,
          },
          ...current.summaries.filter((summary) => summary.id !== plan.id),
        ],
        plan,
      } : current);
    }, "재계획을 요청하지 못했습니다.");
  };

  const createFirstPlan = async () => {
    if (!data) return;
    await mutate(async () => {
      await api.post(
        `/goals/${data.goal.id}/plan-versions`,
        { generationType: "INITIAL" },
        parsePlanVersion,
      );
      await load();
    }, "첫 계획을 만들지 못했습니다.");
  };

  if (loading) return <main className="center-page" aria-busy="true"><p>계획 항로를 불러오고 있습니다.</p></main>;
  if (error && !data) return <main className="center-page error-page" role="alert"><p>{error}</p><button onClick={() => void load()}>다시 불러오기</button></main>;
  if (!data) return <main className="center-page"><p>아직 활성 목표가 없습니다.</p></main>;

  const proposalEvent = data.plan ? eventForPlan(data.plan.id) : null;
  const proposalEventId = proposalEvent?.id ??
    (requestedProposal && requestedProposal.planId === data.plan?.id ? requestedProposal.eventId : null);
  const canDecideProposal = proposalEvent
    ? proposalEvent.userDecision === null
    : proposalEventId !== null;
  return (
    <main className="dashboard-shell">
      <section className="voyage-heading goal-progress-card">
        <p className="eyebrow">{data.goal.remainingMonths}개월의 항로</p>
        <h1>{data.goal.name} 계획</h1>
        <p>{won.format(data.goal.targetAmount - data.goal.currentSavedAmount)} 남음 · 목표일 {data.goal.targetDate}</p>
      </section>
      {error && <p role="alert" className="notice danger">{error}</p>}
      <div className="dashboard-grid">
        <section className="dashboard-main-column" aria-label="계획 버전 항로">
          <h2>버전 항로</h2>
          <ol>
            {data.summaries.map((summary) => <li key={summary.id}><button className="text-button" aria-current={data.plan?.id === summary.id ? "true" : undefined} onClick={() => void showVersion(summary.id)}><strong>v{summary.versionNo}</strong> 계획 보기</button> <span>{summary.generationType} · {summary.status}</span></li>)}
          </ol>
          {!data.plan ? <section className="empty-panel"><h2>아직 만든 계획이 없습니다.</h2><p>현재 목표와 금융 정보로 첫 계획을 계산할 수 있습니다.</p><button className="primary" onClick={() => void createFirstPlan()}>첫 계획 만들기</button><button className="secondary" onClick={() => void load()}>다시 불러오기</button></section> : <><article className="plan-options" aria-label="계획 선택지">
            <h2>{data.plan.status === "PROPOSED" ? "새 계획 제안" : "현재 계획"}</h2>
            {data.plan.options.map((option) => <section key={option.id}>
              <h3>{option.optionType === "CUSTOM" ? "나만의 소비 한도" : `${percent.format(option.nominalLevel ?? 0)} 계획`}</h3>
              <p>월 유동지출 {won.format(option.recommendedMonthlySpending)}</p>
              <p>시뮬레이션 충족률 {percent.format(option.simulationCoverage)}</p>
              {data.plan.status === "PROPOSED" && <button disabled={busyRef.current} onClick={() => void selectOption(option.id)}>{option.optionType === "CUSTOM" ? "나만의 계획 선택" : `${percent.format(option.nominalLevel ?? 0)} 제안 선택`}</button>}
            </section>)}
          </article>
          <label>현실적으로 유지할 월 유동지출<input aria-label="나만의 월 유동지출" inputMode="numeric" value={customSpending} onChange={(event) => setCustomSpending(event.target.value)} /></label>
          <button onClick={() => void createCustom()}>나만의 소비 한도 만들기</button></>}
        </section>
        <aside className="dashboard-sidebar" aria-label="계획 결정 dock">
          {data.plan && <section className="explanation"><h2>계획 설명</h2><p>{data.plan.explanation.status === "FAILED" ? "설명을 준비하지 못했습니다." : data.plan.explanation.text ?? "설명을 정리하고 있습니다."}</p></section>}
          <section className="replan-panel"><h2>결정 dock</h2><button onClick={() => void requestReplan()}>지금 재계획하기</button></section>
          {proposalEventId && canDecideProposal && <section><p>새 계획을 적용할까요?</p><button onClick={() => void decide(proposalEventId, "KEEP_CURRENT_PLAN")}>현재 계획 유지</button></section>}
          {data.events.filter((event) => event.proposedPlanVersionId === null && event.userDecision === null).map((event) => <button key={event.id} onClick={() => void retry(event)}>재계획 다시 시도</button>)}
        </aside>
      </div>
    </main>
  );
};
