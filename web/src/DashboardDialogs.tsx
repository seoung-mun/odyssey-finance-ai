import { useEffect, useRef, useState } from "react";
import { ApiError, type ApiClient } from "./api";
import {
  parseFinancialProfile,
  parseGoalDetail,
  parsePolicyScenario,
  parsePolicySearchResponse,
  parseUserProfile,
  type PolicyAnswer,
  type PolicyResult,
  type PolicyScenario,
  type PolicySearchResponse,
  type PolicySupportGoal,
} from "./types";

const won = new Intl.NumberFormat("ko-KR", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0,
});
const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });
type Mode = "profile" | "financial" | "goal" | "policy";

export const DashboardDialogs = ({
  api,
  goalId,
  currentPlanVersionId,
  onChanged,
}: {
  api: Pick<ApiClient, "get" | "post"> & Partial<Pick<ApiClient, "put" | "patch">>;
  goalId: number;
  currentPlanVersionId: number | null;
  onChanged?: () => void;
}) => {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const launcherRef = useRef<HTMLButtonElement | null>(null);
  const busyRef = useRef(false);
  const sequence = useRef(0);
  const [mode, setMode] = useState<Mode | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [profile, setProfile] = useState({ birthDate: "", regionCode: "" });
  const [financial, setFinancial] = useState({ monthlyIncome: "", monthlyFixedCost: "" });
  const [goal, setGoal] = useState({ name: "", targetAmount: "", currentSavedAmount: "", targetDate: "" });
  const [supportGoal, setSupportGoal] = useState<PolicySupportGoal>("MONTHLY_RENT");
  const [answers, setAnswers] = useState<PolicyAnswer[]>([]);
  const [search, setSearch] = useState<PolicySearchResponse | null>(null);
  const [selectedPolicy, setSelectedPolicy] = useState<PolicyResult | null>(null);
  const [scenarioForm, setScenarioForm] = useState({ amountWon: "", startYearMonth: "", endYearMonth: "" });
  const [scenario, setScenario] = useState<PolicyScenario | null>(null);
  const [scenarioInput, setScenarioInput] = useState(false);

  const clearTransient = () => {
    sequence.current += 1;
    busyRef.current = false;
    setError("");
    setStatus("");
    setAnswers([]);
    setSearch(null);
    setSelectedPolicy(null);
    setScenario(null);
    setScenarioInput(false);
    setScenarioForm({ amountWon: "", startYearMonth: "", endYearMonth: "" });
  };
  const close = () => {
    const launcher = launcherRef.current;
    clearTransient();
    setMode(null);
    if (dialogRef.current?.close) dialogRef.current.close();
    else dialogRef.current?.removeAttribute("open");
    queueMicrotask(() => launcher?.focus());
  };
  useEffect(() => () => {
    sequence.current += 1;
  }, []);

  const open = async (nextMode: Mode, launcher: HTMLButtonElement) => {
    clearTransient();
    launcherRef.current = launcher;
    setMode(nextMode);
    queueMicrotask(() => {
      if (dialogRef.current && !dialogRef.current.open) {
        if (dialogRef.current.showModal) dialogRef.current.showModal();
        else dialogRef.current.setAttribute("open", "");
      }
      dialogRef.current?.querySelector<HTMLElement>(nextMode === "policy" ? "select" : "input")?.focus();
    });
    if (nextMode === "policy") return;
    const current = ++sequence.current;
    setLoading(true);
    try {
      if (nextMode === "profile") {
        const value = await api.get("/me/profile", parseUserProfile);
        if (current === sequence.current)
          setProfile({ birthDate: value.birthDate ?? "", regionCode: value.regionCode ?? "" });
      } else if (nextMode === "financial") {
        const value = await api.get("/me/financial-profile", parseFinancialProfile);
        if (current === sequence.current)
          setFinancial({ monthlyIncome: String(value.monthlyIncome), monthlyFixedCost: String(value.monthlyFixedCost) });
      } else {
        const value = await api.get(`/goals/${goalId}`, parseGoalDetail);
        if (current === sequence.current)
          setGoal({
            name: value.name,
            targetAmount: String(value.targetAmount),
            currentSavedAmount: String(value.currentSavedAmount),
            targetDate: value.targetDate,
          });
      }
    } catch (reason) {
      if (current === sequence.current)
        setError(reason instanceof ApiError ? reason.message : "정보를 불러오지 못했습니다.");
    } finally {
      if (current === sequence.current) setLoading(false);
    }
  };

  const save = async () => {
    if (!mode || mode === "policy" || busyRef.current) return;
    if (!api.put || !api.patch) {
      setError("정보 수정 기능을 사용할 수 없습니다.");
      return;
    }
    busyRef.current = true;
    const current = ++sequence.current;
    setError("");
    setStatus("");
    try {
      if (mode === "profile")
        await api.put("/me/profile", profile, parseUserProfile);
      else if (mode === "financial")
        await api.put(
          "/me/financial-profile",
          {
            monthlyIncome: Number(financial.monthlyIncome),
            monthlyFixedCost: Number(financial.monthlyFixedCost),
          },
          parseFinancialProfile,
        );
      else
        await api.patch(
          `/goals/${goalId}`,
          {
            name: goal.name,
            targetAmount: Number(goal.targetAmount),
            currentSavedAmount: Number(goal.currentSavedAmount),
            targetDate: goal.targetDate,
          },
          parseGoalDetail,
        );
      if (current === sequence.current) {
        setStatus("변경사항을 저장했습니다.");
        onChanged?.();
      }
    } catch (reason) {
      if (current === sequence.current)
        setError(reason instanceof ApiError ? reason.message : "저장하지 못했습니다.");
    } finally {
      if (current === sequence.current) busyRef.current = false;
    }
  };

  const searchPolicies = async (nextAnswers = answers) => {
    if (busyRef.current || nextAnswers.length > 3) return;
    busyRef.current = true;
    const current = ++sequence.current;
    setLoading(true);
    setError("");
    try {
      const value = await api.post(
        "/policies/search",
        { supportGoal, answers: nextAnswers },
        parsePolicySearchResponse,
      );
      if (current === sequence.current) {
        if (value.type === "QUESTION" && nextAnswers.length >= 3) {
          setAnswers([]);
          setSearch(null);
          setSelectedPolicy(null);
          setScenario(null);
          setScenarioInput(false);
          setScenarioForm({ amountWon: "", startYearMonth: "", endYearMonth: "" });
          setError("추가 질문은 최대 3개까지 가능합니다. 처음부터 다시 검색해 주세요.");
        } else {
          setSearch(value);
        }
      }
    } catch (reason) {
      if (current === sequence.current)
        setError(reason instanceof ApiError ? reason.message : "정책을 찾지 못했습니다.");
    } finally {
      if (current === sequence.current) {
        busyRef.current = false;
        setLoading(false);
      }
    }
  };
  const answerQuestion = (value: string) => {
    if (search?.type !== "QUESTION" || answers.length >= 3) return;
    const next = [...answers, { questionId: search.question.questionId, value }];
    setAnswers(next);
    void searchPolicies(next);
  };
  const compare = async () => {
    if (!selectedPolicy || !currentPlanVersionId || busyRef.current) return;
    const amountWon = Number(scenarioForm.amountWon);
    if (!Number.isSafeInteger(amountWon) || amountWon < 1 || !scenarioForm.startYearMonth) {
      setError("기관이 확정한 지원금과 적용 월을 확인해 주세요.");
      return;
    }
    busyRef.current = true;
    const current = ++sequence.current;
    setLoading(true);
    setError("");
    try {
      const monthly = selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION";
      const confirmedAward = {
        type: selectedPolicy.calculationMode,
        institutionConfirmed: true,
        amountWon,
        startYearMonth: scenarioForm.startYearMonth,
        ...(monthly ? { endYearMonth: scenarioForm.endYearMonth } : {}),
      };
      const value = await api.post(
        `/policy-versions/${selectedPolicy.policyVersionId}/scenario`,
        { currentPlanVersionId, supportGoal, answers, confirmedAward },
        parsePolicyScenario,
      );
      if (current === sequence.current) setScenario(value);
    } catch (reason) {
      if (current === sequence.current) {
        const apiError = reason instanceof ApiError ? reason : null;
        setScenario(null);
        if (apiError?.status === 409) {
          setAnswers([]);
          setSearch(null);
          setSelectedPolicy(null);
          setScenarioInput(false);
          setScenarioForm({ amountWon: "", startYearMonth: "", endYearMonth: "" });
        }
        setError(
          apiError?.status === 409
            ? "정책 정보가 변경되었습니다. 정책을 다시 검색해 주세요."
            : apiError?.status === 503
              ? "정책 반영 비교를 만들지 못했습니다. 다시 시도해 주세요."
              : apiError?.message ?? "비교를 만들지 못했습니다.",
        );
      }
    } finally {
      if (current === sequence.current) {
        busyRef.current = false;
        setLoading(false);
      }
    }
  };

  return (
    <section className="dashboard-tools" aria-label="내 정보와 정책">
      <div className="inline-actions">
        <button className="secondary" onClick={(event) => void open("profile", event.currentTarget)}>인적 정보 수정</button>
        <button className="secondary" onClick={(event) => void open("financial", event.currentTarget)}>재무 정보 수정</button>
        <button className="secondary" onClick={(event) => void open("goal", event.currentTarget)}>목표 수정</button>
      </div>
      <button className="primary policy-launcher" onClick={(event) => void open("policy", event.currentTarget)}>
        주거정책 탐색
      </button>
      <dialog
        ref={dialogRef}
        aria-labelledby="dashboard-dialog-title"
        className={mode === "policy" ? "dashboard-dialog policy-dialog" : "dashboard-dialog"}
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
      >
        <div className="dialog-heading">
          <div>
            <p className="eyebrow">{mode === "policy" ? "저장하지 않는 탐색" : "현재 정보"}</p>
            <h2 id="dashboard-dialog-title">
              {mode === "profile" ? "인적 정보 수정" : mode === "financial" ? "재무 정보 수정" : mode === "goal" ? "목표 수정" : "주거정책 탐색"}
            </h2>
          </div>
          <button className="text-button" onClick={close}>닫기</button>
        </div>
        {error && <p role="alert" className="notice danger">{error}</p>}
        {status && <p role="status" className="notice">{status}</p>}
        {loading && <p role="status" aria-busy="true">정보를 확인하고 있습니다.</p>}
        {mode === "profile" && (
          <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <label>생년월일<input type="date" value={profile.birthDate} onChange={(event) => setProfile({ ...profile, birthDate: event.target.value })} /></label>
            <label>지역 코드<input inputMode="numeric" pattern="[0-9]{5}" value={profile.regionCode} onChange={(event) => setProfile({ ...profile, regionCode: event.target.value })} /></label>
            <button className="primary">변경사항 저장</button>
          </form>
        )}
        {mode === "financial" && (
          <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <label>월 소득<input type="number" min="0" value={financial.monthlyIncome} onChange={(event) => setFinancial({ ...financial, monthlyIncome: event.target.value })} /></label>
            <label>월 고정비<input type="number" min="0" value={financial.monthlyFixedCost} onChange={(event) => setFinancial({ ...financial, monthlyFixedCost: event.target.value })} /></label>
            <button className="primary">변경사항 저장</button>
          </form>
        )}
        {mode === "goal" && (
          <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <label>목표 이름<input required value={goal.name} onChange={(event) => setGoal({ ...goal, name: event.target.value })} /></label>
            <label>목표 금액<input required type="number" min="1" value={goal.targetAmount} onChange={(event) => setGoal({ ...goal, targetAmount: event.target.value })} /></label>
            <label>현재 모은 금액<input required type="number" min="0" value={goal.currentSavedAmount} onChange={(event) => setGoal({ ...goal, currentSavedAmount: event.target.value })} /></label>
            <label>목표일<input required type="date" value={goal.targetDate} onChange={(event) => setGoal({ ...goal, targetDate: event.target.value })} /></label>
            <button className="primary">변경사항 저장</button>
          </form>
        )}
        {mode === "policy" && (
          <div className="policy-route">
            <section className="policy-route-step" data-active={!search}>
              <span aria-hidden="true">1</span>
              <div><h3>지원 목표</h3><label>지원 목표<select value={supportGoal} onChange={(event) => setSupportGoal(event.target.value as PolicySupportGoal)}><option value="MONTHLY_RENT">월세</option><option value="JEONSE">전세</option><option value="PURCHASE">주택 구입</option><option value="PUBLIC_RENTAL">공공임대</option><option value="SUBSCRIPTION">청약</option><option value="MOVING_COST">이사비</option><option value="GUARANTEE">보증</option><option value="DORMITORY">기숙사</option></select></label><button className="primary" disabled={loading} onClick={() => void searchPolicies()}>정책 찾기</button></div>
            </section>
            {search?.type === "QUESTION" && (
              <section className="policy-route-step" data-active><span aria-hidden="true">2</span><div><h3>추가 확인 {answers.length + 1}/3</h3><label>{search.question.label}<select defaultValue="" onChange={(event) => answerQuestion(event.target.value)}><option value="" disabled>선택해 주세요</option>{search.question.options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div></section>
            )}
            {search?.type === "RESULTS" && !selectedPolicy && (
              <section className="policy-route-step" data-active><span aria-hidden="true">2</span><div><h3>공식 정책 Top 3</h3><div className="policy-results">{search.results.map((policy) => <button className="policy-result" key={policy.policyVersionId} onClick={() => setSelectedPolicy(policy)}><strong>{policy.title}</strong><small>{policy.summary}</small></button>)}</div>{search.results.length === 0 && <p>조건에 맞는 정책을 찾지 못했습니다.</p>}</div></section>
            )}
            {selectedPolicy && (
              <section className="policy-route-step" data-active={!scenarioInput}><span aria-hidden="true">3</span><div><h3>{selectedPolicy.title}</h3><p>{selectedPolicy.summary}</p><p>{selectedPolicy.planConnection}</p><p>{selectedPolicy.supportDetails}</p><p><strong>신청 기간</strong> {selectedPolicy.applicationPeriod}</p><p>실제 지원 여부는 신청기관이 확정합니다.</p><a href={selectedPolicy.source.officialUrl} target="_blank" rel="noreferrer">{selectedPolicy.source.organization} 공식 원문</a>{(selectedPolicy.calculationMode === "ONE_TIME_FUNDING" || selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION") && currentPlanVersionId && <button className="secondary" onClick={() => setScenarioInput(true)}>정책 반영 가정으로 비교</button>}</div></section>
            )}
            {selectedPolicy && scenarioInput && (
              <section className="policy-route-step" data-active><span aria-hidden="true">4</span><div><h3>기관 확정 값으로 가상 비교</h3><label>기관 확정 지원금<input type="number" min="1" value={scenarioForm.amountWon} onChange={(event) => setScenarioForm({ ...scenarioForm, amountWon: event.target.value })} /></label><label>적용 월<input type="month" value={scenarioForm.startYearMonth} onChange={(event) => setScenarioForm({ ...scenarioForm, startYearMonth: event.target.value })} /></label>{selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION" && <label>종료 월<input type="month" value={scenarioForm.endYearMonth} onChange={(event) => setScenarioForm({ ...scenarioForm, endYearMonth: event.target.value })} /></label>}<button className="primary" disabled={loading} onClick={() => void compare()}>현재 계획과 비교</button>{scenario && <div className="policy-comparison" role="status"><dl><div><dt>현재 계획 월 지출</dt><dd>{won.format(scenario.currentPlanSummary.recommendedMonthlySpending)}</dd></div><div><dt>가정 계획 월 지출</dt><dd>{won.format(scenario.assumedPlanSummary.recommendedMonthlySpending)}</dd></div><div><dt>현재 시뮬레이션 충족률</dt><dd>{percent.format(scenario.currentPlanSummary.simulationCoverage)}</dd></div><div><dt>가정 시뮬레이션 충족률</dt><dd>{percent.format(scenario.assumedPlanSummary.simulationCoverage)}</dd></div></dl><p>{scenario.assumptionNotice}</p></div>}</div></section>
            )}
          </div>
        )}
      </dialog>
    </section>
  );
};
