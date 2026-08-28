import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";
import {
  parseDashboard,
  parseExplanation,
  parseObject,
  parsePlanVersion,
  parseReplanEvents,
  type Dashboard,
  type PercentileBand,
  type PlanVersion,
} from "./types";

const won = new Intl.NumberFormat("ko-KR", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0,
});
const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });

const points = (bands: PercentileBand[], key: "p10" | "p25" | "p50" | "p75" | "p90") => {
  if (!bands.length) return "";
  const values = bands.flatMap((band) => [band.p10, band.p90]);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  return bands
    .map(
      (band, index) =>
        `${20 + (index * 560) / Math.max(bands.length - 1, 1)},${220 - ((band[key] - min) / range) * 180}`,
    )
    .join(" ");
};

const GoalPath = ({ bands }: { bands: PercentileBand[] }) => {
  const upper = points(bands, "p90").split(" ");
  const lower = points(bands, "p10").split(" ").reverse();
  return (
    <figure className="goal-path">
      <svg viewBox="0 0 620 250" role="img" aria-label="목표까지의 저축 예상 범위 fan chart">
        <defs>
          <linearGradient id="sea" x1="0" x2="1">
            <stop stopColor="#37d6bb" stopOpacity=".08" />
            <stop offset="1" stopColor="#37d6bb" stopOpacity=".42" />
          </linearGradient>
        </defs>
        <path d="M20 220 H600" className="chart-axis" />
        {bands.length > 0 && <polygon points={[...upper, ...lower].join(" ")} fill="url(#sea)" />}
        <polyline points={points(bands, "p50")} className="median-path" />
        <circle
          cx="600"
          cy={points(bands, "p50").split(" ").slice(-1)[0]?.split(",")[1] ?? 40}
          r="7"
          className="destination"
        />
      </svg>
      <figcaption>
        <span>현재</span>
        <span>목표일</span>
      </figcaption>
    </figure>
  );
};

export const DashboardPage = ({ api }: { api: Pick<ApiClient, "get" | "post"> }) => {
  const navigate = useNavigate();
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [refreshed, setRefreshed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [pollTimedOut, setPollTimedOut] = useState(false);
  const [proposal, setProposal] = useState<PlanVersion | null>(null);
  const [proposalEventId, setProposalEventId] = useState<number | null>(null);
  const [proposalAccepted, setProposalAccepted] = useState(false);
  const [replanning, setReplanning] = useState(false);
  const [replanError, setReplanError] = useState("");
  const replanBusy = useRef(false);
  const loadSequence = useRef(0);
  const errorRef = useRef<HTMLElement>(null);

  const load = useCallback(
    async (retryConflict = true) => {
      const sequence = ++loadSequence.current;
      setLoading(true);
      setError(null);
      setPollTimedOut(false);
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
            apiError =
              retryReason instanceof ApiError ? retryReason : new ApiError(0, "NETWORK_ERROR");
          }
        }
        if (apiError.status === 403) navigate("/forbidden", { replace: true });
        else if (apiError.status === 404) navigate("/not-found", { replace: true });
        else if (sequence === loadSequence.current) setError(apiError);
      } finally {
        if (sequence === loadSequence.current) setLoading(false);
      }
    },
    [api, navigate],
  );

  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (error) errorRef.current?.focus();
  }, [error]);

  const planId = data?.activePlan?.id;
  const explanationStatus = data?.activePlan?.explanation.status;
  const shouldPollExplanation =
    explanationStatus === "PENDING" || explanationStatus === "PROCESSING";
  useEffect(() => {
    if (!planId || !shouldPollExplanation) return;
    let cancelled = false;
    let attempts = 0;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      attempts += 1;
      try {
        const explanation = parseExplanation(
          await api.get(`/plan-versions/${planId}/explanation`, parseExplanation),
        );
        if (cancelled) return;
        setData((current) =>
          current?.activePlan
            ? { ...current, activePlan: { ...current.activePlan, explanation } }
            : current,
        );
        if (
          explanation.status === "READY" ||
          explanation.status === "FALLBACK" ||
          explanation.status === "FAILED"
        )
          return;
      } catch {
        if (cancelled) return;
      }
      if (attempts < 15) timer = setTimeout(poll, 2000);
      else if (!cancelled) setPollTimedOut(true);
    };
    timer = setTimeout(poll, 2000);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, planId, shouldPollExplanation]);

  const requestReplan = async () => {
    if (!data?.goal || replanBusy.current) return;
    replanBusy.current = true;
    setReplanning(true);
    setReplanError("");
    try {
      const response = parseObject(
        await api.post(`/goals/${data.goal.id}/replan`, undefined, parseObject),
      );
      const eventId = response.replanEventId;
      if (!Number.isSafeInteger(eventId) || Number(eventId) < 1) throw new Error("INVALID_RESPONSE");
      setProposal(parsePlanVersion(response));
      setProposalEventId(Number(eventId));
      setProposalAccepted(false);
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      if (apiError.status === 409) await load(false);
      else if (apiError.status === 403) navigate("/forbidden", { replace: true });
      else if (apiError.status === 404) navigate("/not-found", { replace: true });
      else if (apiError.status === 422)
        setReplanError("현재 조건으로는 재계획할 수 없습니다. 목표 금액이나 날짜를 조정해 주세요.");
      else
        setReplanError(
          `${apiError.requestId ? `요청 ID ${apiError.requestId}. ` : ""}재계획하지 못했습니다. 잠시 후 다시 시도해 주세요.`,
        );
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
          await api.post(
            `/replan-events/${proposalEventId}/decision`,
            { decision: "ACCEPT_NEW_PLAN" },
            parseObject,
          );
        } catch (reason) {
          if (!(reason instanceof ApiError) || reason.status !== 409 || !data?.goal) throw reason;
          const events = parseReplanEvents(
            await api.get(`/goals/${data.goal.id}/replan-events`, parseReplanEvents),
          );
          const confirmed = events.some(
            (event) =>
              event.id === proposalEventId &&
              event.userDecision === "ACCEPT_NEW_PLAN" &&
              event.proposedPlanVersionId === proposal.id,
          );
          if (!confirmed) throw reason;
        }
        setProposalAccepted(true);
      }
      await api.post(
        `/plan-versions/${proposal.id}/select-option`,
        { planOptionId: optionId },
        parsePlanVersion,
      );
      setProposal(null);
      setProposalEventId(null);
      setProposalAccepted(false);
      await load(false);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR"));
    } finally {
      replanBusy.current = false;
      setReplanning(false);
    }
  };

  if (loading)
    return (
      <main className="center-page" aria-busy="true">
        <p className="eyebrow">항로 확인 중</p>
        <h1>최신 계획을 불러오고 있습니다</h1>
      </main>
    );
  if (error)
    return (
      <main ref={errorRef} tabIndex={-1} className="center-page error-page">
        <p className="eyebrow">{error.status === 0 ? "네트워크 단절" : "서버 오류"}</p>
        <h1>{error.status === 0 ? "연결을 확인해 주세요" : "잠시 후 다시 확인해 주세요"}</h1>
        {error.requestId && (
          <p>
            요청 ID: <code>{error.requestId}</code>
          </p>
        )}
        <button className="primary" onClick={() => void load(false)}>
          다시 불러오기
        </button>
      </main>
    );
  if (!data?.goal)
    return (
      <main className="center-page">
        <p className="eyebrow">첫 번째 목적지</p>
        <h1>아직 목표가 없습니다</h1>
        <p>원하는 금액과 날짜를 정하면 첫 계획을 만듭니다.</p>
        <Link className="primary link-button" to="/onboarding">
          목표 만들기
        </Link>
      </main>
    );

  const { goal, activePlan, selectedOption, monthProgress } = data;
  const infeasible = activePlan?.status === "INFEASIBLE";
  return (
    <main className="dashboard-shell">
      <header className="topbar">
        <span className="brand-mark">ODYSSEY</span>
        <nav aria-label="주요 메뉴">
          <Link to="/dashboard" aria-current="page">
            항로
          </Link>
          <Link to="/onboarding">정보 수정</Link>
        </nav>
      </header>
      {refreshed && (
        <p role="status" className="notice">
          최신 상태를 불러왔습니다.
        </p>
      )}
      {data.pendingProposal && (
        <section className="notice proposal">
          <strong>새 계획이 도착했습니다.</strong>
          <span>현실의 변화를 반영한 경로를 확인해 주세요.</span>
        </section>
      )}
      {infeasible && (
        <section className="notice danger">
          <strong>현재 조건으로는 목표에 닿기 어렵습니다.</strong>
          <span>{activePlan.infeasibleReason}</span>
          <div className="inline-actions">
            <Link to="/onboarding?step=goal">목표 줄이기</Link>
            <Link to="/onboarding?step=goal">기간 늘리기</Link>
            <Link to="/onboarding">입력 확인하기</Link>
          </div>
        </section>
      )}
      <section className="voyage-heading">
        <div>
          <p className="eyebrow">{goal.remainingMonths}개월의 항로</p>
          <h1>
            {goal.name}까지
            <br />
            <em>{won.format(goal.targetAmount - goal.currentSavedAmount)}</em> 남았습니다
          </h1>
        </div>
        <dl>
          <div>
            <dt>현재 모은 금액</dt>
            <dd>{won.format(goal.currentSavedAmount)}</dd>
          </div>
          <div>
            <dt>목표일</dt>
            <dd>{goal.targetDate}</dd>
          </div>
        </dl>
      </section>
      {selectedOption ? (
        <>
          <GoalPath bands={selectedOption.percentileBands} />
          <section className="instrument-strip" aria-label="현재 계획 지표">
            <dl>
              <dt>월 유동지출 항로</dt>
              <dd>{won.format(selectedOption.recommendedMonthlySpending)}</dd>
            </dl>
            <dl>
              <dt>시뮬레이션 충족률</dt>
              <dd>{percent.format(selectedOption.simulationCoverage)}</dd>
            </dl>
            <dl>
              <dt>이번 달 사용</dt>
              <dd>{monthProgress ? won.format(monthProgress.actualToDate) : "자료 없음"}</dd>
            </dl>
          </section>
          {selectedOption.floorApplied && activePlan && (
            <p className="floor-note">
              생활 하한선{" "}
              {won.format(activePlan.snapshot.resolvedSpendingFloor.effectiveMonthlyAmount)}을 지킨
              추천입니다.
            </p>
          )}
          {selectedOption.aggressiveWarning && (
            <p role="alert" className="notice warning">
              이 계획은 최근 소비패턴보다 상당히 낮은 수준입니다. 소비내역을 확인해 주세요.
            </p>
          )}
          {selectedOption.targetCoverageMet === false && (
            <p role="alert" className="notice warning">
              선택한 월 지출은 목표 안정성 수준에 미치지 못합니다. 더 낮은 지출 계획이나 목표 조정을
              검토해 주세요.
            </p>
          )}
        </>
      ) : (
        <section className="empty-panel">
          <h2>선택한 계획이 없습니다</h2>
          <p>제안된 계획에서 유지할 수 있는 월 지출을 선택해 주세요.</p>
        </section>
      )}
      {activePlan?.explanation.status === "FALLBACK" && (
        <section className="explanation">
          <p className="eyebrow">기본 안내</p>
          <p>{activePlan.explanation.text ?? "계획 수치와 경로를 기준으로 선택해 주세요."}</p>
        </section>
      )}
      {activePlan?.explanation.status === "READY" && (
        <section className="explanation">
          <p className="eyebrow">계획 설명</p>
          <p>{activePlan.explanation.text}</p>
        </section>
      )}
      {(activePlan?.explanation.status === "PENDING" ||
        activePlan?.explanation.status === "PROCESSING") && (
        <section className="explanation" aria-live="polite">
          <p>계획 설명을 정리하고 있습니다. 계산된 계획은 지금 확인할 수 있습니다.</p>
        </section>
      )}
      {pollTimedOut && (
        <p role="status" className="notice warning">
          설명 생성 시간이 길어지고 있습니다. 설명 없이 계획을 확인해 주세요.
        </p>
      )}
      <section className="replan-panel">
        <div>
          <p className="eyebrow">현재 소비 반영</p>
          <h2>지금 기준으로 항로 다시 계산하기</h2>
          <p>기존 계획은 새 계획을 선택하기 전까지 유지됩니다.</p>
        </div>
        <button className="secondary" disabled={replanning} onClick={() => void requestReplan()}>
          지금 재계획하기
        </button>
      </section>
      {replanError && (
        <p role="alert" className="notice danger">
          {replanError}
        </p>
      )}
      {proposal && (
        <section className="plan-options" aria-label="새 계획 선택지">
          {proposal.options
            .filter((option) => option.optionType === "PRESET")
            .map((option) => (
              <article key={option.id}>
                <p className="eyebrow">{percent.format(option.nominalLevel ?? 0)} 안정성 수준</p>
                <h2>{won.format(option.recommendedMonthlySpending)}</h2>
                <p>시뮬레이션 충족률 {percent.format(option.simulationCoverage)}</p>
                <button
                  className="primary"
                  disabled={replanning}
                  onClick={() => void selectProposal(option.id)}
                >
                  {percent.format(option.nominalLevel ?? 0)} 새 계획 선택
                </button>
              </article>
            ))}
        </section>
      )}
    </main>
  );
};
