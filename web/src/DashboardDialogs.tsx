import { useEffect, useRef, useState } from "react";
import { ApiError, type ApiClient } from "./api";
import { AiAssistantDrawer, type AssistantPlanContext } from "./AiAssistantDrawer";
import { formatMoneyCompact } from "./formatMoney";
import { SavingsRecommendationDialog } from "./SavingsRecommendationDialog";
import {
  parseFinancialProfile,
  parseGoalDetail,
  parsePolicyBenefit,
  parsePolicyBenefits,
  parsePolicyScenario,
  parsePolicySearchResponse,
  parseUserProfile,
  type PolicyAnswer,
  type PolicyBenefit,
  type PolicyResult,
  type PolicyScenario,
  type PolicySearchResponse,
  type PolicySupportGoal,
} from "./types";

const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });
type EditMode = "profile" | "financial" | "goal";
type Mode = "menu" | EditMode | "policy";
const isCalculablePolicy = (policy: PolicyResult) =>
  policy.calculationMode === "ONE_TIME_FUNDING" ||
  policy.calculationMode === "MONTHLY_EXPENSE_REDUCTION";
const policyModeLabel = (mode: PolicyResult["calculationMode"]) => ({
  INFORMATIONAL: "정보 안내",
  ELIGIBILITY_ONLY: "자격 확인형",
  ONE_TIME_FUNDING: "일시 지원",
  MONTHLY_EXPENSE_REDUCTION: "월 지출 지원",
})[mode];
const policyTextSegments = (value: string) =>
  value.split(/\r?\n|(?=[○※•])|\s*·\s*|(?<=[.!?])\s+/u).map((item) => item.trim()).filter(Boolean);

export const DashboardDialogs = ({
  api,
  goalId,
  currentPlanVersionId,
  onChanged,
  onRequestReplan,
  onOpenTransactions,
  assistantContext = {
    goalName: "현재 목표",
    currentSavedAmount: 0,
    targetAmount: 0,
    targetDate: "",
    monthlySpending: null,
    stability: null,
  },
}: {
  api: Pick<ApiClient, "get" | "post"> & Partial<Pick<ApiClient, "put" | "patch" | "delete">>;
  goalId: number;
  currentPlanVersionId: number | null;
  onChanged?: () => void;
  onRequestReplan?: (openComparison?: boolean) => Promise<boolean>;
  onOpenTransactions?: () => void;
  assistantContext?: AssistantPlanContext;
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
  const [benefitInput, setBenefitInput] = useState(false);
  const [benefitForm, setBenefitForm] = useState({ institutionConfirmed: false, amountWon: "", startYearMonth: "", endYearMonth: "" });
  const [benefits, setBenefits] = useState<PolicyBenefit[]>([]);
  const [benefitsLoading, setBenefitsLoading] = useState(false);
  const [savingsOpen, setSavingsOpen] = useState(false);
  const [assistantOpen, setAssistantOpen] = useState(false);
  const [assistantPlanOverview, setAssistantPlanOverview] = useState(false);

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
    setBenefitInput(false);
    setBenefitForm({ institutionConfirmed: false, amountWon: "", startYearMonth: "", endYearMonth: "" });
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

  const loadBenefits = async () => {
    setBenefitsLoading(true);
    try {
      setBenefits(parsePolicyBenefits(await api.get(`/goals/${goalId}/policy-benefits`, parsePolicyBenefits)));
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "확정된 지원 내역을 불러오지 못했습니다.");
    } finally {
      setBenefitsLoading(false);
    }
  };

  const open = async (nextMode: Mode, launcher: HTMLButtonElement | null) => {
    clearTransient();
    if (!dialogRef.current?.open) launcherRef.current = launcher;
    setMode(nextMode);
    queueMicrotask(() => {
      if (dialogRef.current && !dialogRef.current.open) {
        if (dialogRef.current.showModal) dialogRef.current.showModal();
        else dialogRef.current.setAttribute("open", "");
      }
      dialogRef.current?.querySelector<HTMLElement>(nextMode === "policy" ? "select" : "input")?.focus();
    });
    if (nextMode === "policy") {
      void loadBenefits();
      return;
    }
    if (nextMode === "menu") return;
    const current = ++sequence.current;
    setLoading(true);
    try {
      if (nextMode === "profile") {
        const value = parseUserProfile(await api.get("/me/profile", parseUserProfile));
        if (current === sequence.current)
          setProfile({ birthDate: value.birthDate ?? "", regionCode: value.regionCode ?? "" });
      } else if (nextMode === "financial") {
        const value = parseFinancialProfile(await api.get("/me/financial-profile", parseFinancialProfile));
        if (current === sequence.current)
          setFinancial({ monthlyIncome: String(value.monthlyIncome), monthlyFixedCost: String(value.monthlyFixedCost) });
      } else {
        const value = parseGoalDetail(await api.get(`/goals/${goalId}`, parseGoalDetail));
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
    if (!mode || mode === "policy" || mode === "menu" || busyRef.current) return;
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
      const value = parsePolicySearchResponse(
        await api.post(
          "/policies/search",
          { supportGoal, answers: nextAnswers },
          parsePolicySearchResponse,
        ),
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
    const monthly = selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION";
    if (!Number.isSafeInteger(amountWon) || amountWon < 1 || !scenarioForm.startYearMonth || (monthly && !scenarioForm.endYearMonth)) {
      setError("기관이 확정한 지원금과 적용 월을 확인해 주세요.");
      return;
    }
    busyRef.current = true;
    const current = ++sequence.current;
    setLoading(true);
    setError("");
    try {
      const confirmedAward = {
        type: selectedPolicy.calculationMode,
        institutionConfirmed: true,
        amountWon,
        startYearMonth: scenarioForm.startYearMonth,
        ...(monthly ? { endYearMonth: scenarioForm.endYearMonth } : {}),
      };
      const value = parsePolicyScenario(
        await api.post(
          `/policy-versions/${selectedPolicy.policyVersionId}/scenario`,
          { currentPlanVersionId, supportGoal, answers, confirmedAward },
          parsePolicyScenario,
        ),
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

  const confirmBenefit = async () => {
    if (!selectedPolicy || busyRef.current) return;
    const amountWon = Number(benefitForm.amountWon);
    const monthly = selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION";
    if (!benefitForm.institutionConfirmed || !Number.isSafeInteger(amountWon) || amountWon < 1 || !benefitForm.startYearMonth || (monthly && !benefitForm.endYearMonth)) {
      setError("기관 확정 여부와 지원 금액·적용 기간을 확인해 주세요.");
      return;
    }
    busyRef.current = true;
    setLoading(true);
    setError("");
    let benefit: PolicyBenefit;
    try {
      benefit = parsePolicyBenefit(await api.post(
        `/policy-versions/${selectedPolicy.policyVersionId}/benefits`,
        {
          goalId,
          institutionConfirmed: true,
          amountWon,
          startYearMonth: benefitForm.startYearMonth,
          ...(monthly ? { endYearMonth: benefitForm.endYearMonth } : {}),
        },
        parsePolicyBenefit,
      ));
    } catch (reason) {
      const apiError = reason instanceof ApiError ? reason : null;
      setError(apiError?.status === 409
        ? "이미 확정된 지원 내용과 금액 또는 기간이 다릅니다. 기존 내역을 확인해 주세요."
        : apiError?.message ?? "확정된 지원 내용을 저장하지 못했습니다.");
      busyRef.current = false;
      setLoading(false);
      return;
    }
    setBenefits((current) => [benefit, ...current.filter((item) => item.id !== benefit.id)]);
    if (!onRequestReplan) {
      setError("지원 내용은 저장되었지만 재계획 기능을 사용할 수 없습니다.");
      busyRef.current = false;
      setLoading(false);
      return;
    }
    let replanned = false;
    try {
      replanned = await onRequestReplan(false);
    } catch {
      replanned = false;
    }
    busyRef.current = false;
    setLoading(false);
    if (replanned) close();
    else setError("지원 내용은 저장되었지만 재계획을 시작하지 못했습니다.");
  };

  const cancelBenefit = async (benefitId: number) => {
    if (!api.delete || busyRef.current) {
      if (!api.delete) setError("지원 취소 기능을 사용할 수 없습니다.");
      return;
    }
    busyRef.current = true;
    setError("");
    try {
      const cancelled = parsePolicyBenefit(await api.delete(`/policy-benefits/${benefitId}`, parsePolicyBenefit));
      setBenefits((current) => current.filter((item) => item.id !== cancelled.id));
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "확정된 지원을 취소하지 못했습니다.");
    } finally {
      busyRef.current = false;
    }
  };

  const policyStage = benefitInput ? 4 : scenarioInput ? 3 : selectedPolicy ? 2 : search?.type === "RESULTS" ? 1 : 0;

  return (
    <div className="dashboard-tools" aria-label="Odyssey AI 금융 도구">
      {!assistantOpen && <section className="dashboard-card odyssey-ai-card">
        <div className="odyssey-ai-heading">
          <span aria-hidden="true">✦</span>
          <div><p>ODYSSEY AI</p><h2>현재 계획을 확인했어요.</h2></div>
        </div>
        <p className="odyssey-ai-intro">무엇을 도와드릴까요?</p>
        <div className="odyssey-ai-quick-actions">
          <button type="button" aria-label="주거정책 찾기" onClick={(event) => void open("policy", event.currentTarget)}>내게 맞는 주거정책 찾아줘 <span aria-hidden="true">→</span></button>
          <button type="button" aria-label="현재 계획에 맞는 적금 추천" onClick={() => setSavingsOpen(true)}>내 계획에 맞는 적금 추천해줘 <span aria-hidden="true">→</span></button>
          <button type="button" onClick={() => { setAssistantPlanOverview(true); setAssistantOpen(true); }}>내 계획 점검해줘 <span aria-hidden="true">→</span></button>
        </div>
        <button type="button" className="odyssey-ai-composer-launcher" aria-label="AI 도우미 열기" onClick={() => { setAssistantPlanOverview(false); setAssistantOpen(true); }}>
          <span>Odyssey AI에게 물어보기...</span><i aria-hidden="true">↑</i>
        </button>
      </section>}
      <dialog
        ref={dialogRef}
        aria-labelledby="dashboard-dialog-title"
        className={mode === "policy" ? "dashboard-dialog policy-dialog" : "dashboard-dialog"}
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
      >
        {mode === "policy" ? (
          <header className="policy-dialog-header">
            <div><span>저장하지 않는 탐색</span><h2 id="dashboard-dialog-title">주거정책 탐색</h2><p>Odyssey AI가 내 조건에 맞는 주거지원을 함께 확인해요.</p></div>
            <button type="button" aria-label="닫기" onClick={close}>×</button>
          </header>
        ) : (
          <div className="dialog-heading">
            <div>
              <p className="eyebrow">현재 정보</p>
              <h2 id="dashboard-dialog-title">
                {mode === "menu" ? "나의 계획 정보" : mode === "profile" ? "인적 정보 수정" : mode === "financial" ? "재무 정보 수정" : mode === "goal" ? "목표 수정" : "주거정책 탐색"}
              </h2>
            </div>
            <button className="text-button" onClick={close}>닫기</button>
          </div>
        )}
        {error && <p role="alert" className="notice danger">{error}</p>}
        {status && <p role="status" className="notice">{status}</p>}
        {loading && <p role="status" aria-busy="true">정보를 확인하고 있습니다.</p>}
        {mode === "menu" && (
          <div className="dashboard-edit-menu">
            <button className="secondary" onClick={(event) => void open("profile", event.currentTarget)}>인적 정보 수정</button>
            <button className="secondary" onClick={(event) => void open("financial", event.currentTarget)}>재무 정보 수정</button>
            <button className="secondary" onClick={(event) => void open("goal", event.currentTarget)}>목표 수정</button>
          </div>
        )}
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
            <ol className="policy-step-indicator" aria-label="정책 탐색 단계">
              {["조건 선택", "추천", "상세", "계획 비교", "확정"].map((label, index) => <li key={label} data-active={index === policyStage} data-complete={index < policyStage} aria-current={index === policyStage ? "step" : undefined}>{label}</li>)}
            </ol>
            <section className="policy-route-step" data-active={!search}>
              <span aria-hidden="true">1</span>
              <div><h3>지원 목표</h3><label>지원 목표<select value={supportGoal} onChange={(event) => setSupportGoal(event.target.value as PolicySupportGoal)}><option value="MONTHLY_RENT">월세</option><option value="JEONSE">전세</option><option value="PURCHASE">주택 구입</option><option value="PUBLIC_RENTAL">공공임대</option><option value="SUBSCRIPTION">청약</option><option value="MOVING_COST">이사비</option><option value="GUARANTEE">보증</option><option value="DORMITORY">기숙사</option></select></label><button className="primary" disabled={loading} onClick={() => void searchPolicies()}>정책 찾기</button></div>
            </section>
            {search?.type === "QUESTION" && (
              <section className="policy-route-step" data-active><span aria-hidden="true">2</span><div><h3>추가 확인 {answers.length + 1}/3</h3><label>{search.question.label}<select defaultValue="" onChange={(event) => answerQuestion(event.target.value)}><option value="" disabled>선택해 주세요</option>{search.question.options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div></section>
            )}
            {search?.type === "RESULTS" && !selectedPolicy && (
              <section className="policy-route-step" data-active>
                <span aria-hidden="true">2</span>
                <div>
                  <div className="policy-results-heading"><h3>추천 정책 Top 3</h3><small>공식 출처와 내 조건을 함께 확인했어요.</small></div>
                  <div className="policy-results">
                    {search.results.map((policy, index) => (
                      <article className="policy-result" data-featured={index === 0} key={policy.policyVersionId}>
                        <div className="policy-result-rank"><span>✦ ODYSSEY AI 추천 {index + 1}</span>{index === 0 && <strong>가장 먼저 확인</strong>}</div>
                        {index === 0 && <p className="policy-featured-copy">가장 먼저 확인해볼 정책이에요.</p>}
                        <h4>{policy.title}</h4>
                        <p className="policy-result-benefit">{policy.supportDetails}</p>
                        <dl className="policy-result-meta"><div><dt>신청기관</dt><dd>{policy.source.organization}</dd></div><div><dt>지원 형태</dt><dd>{policyModeLabel(policy.calculationMode)}</dd></div></dl>
                        <strong className="policy-checks-title">왜 추천했나요?</strong>
                        <ul className="policy-condition-preview">
                          {[
                            ...policy.confirmedConditions.map((condition) => ({ condition, type: "confirmed" as const })),
                            ...policy.additionalChecks.map((condition) => ({ condition, type: "check" as const })),
                          ].slice(0, 2).map(({ condition, type }) => <li className={type} key={`${type}-${condition}`}><span aria-hidden="true">{type === "confirmed" ? "✓" : "?"}</span><span>{condition}</span></li>)}
                          {policy.confirmedConditions.length === 0 && policy.additionalChecks.length === 0 && <li className="check"><span aria-hidden="true">?</span><span>공식 페이지에서 직접 확인이 필요해요.</span></li>}
                        </ul>
                        <div className="policy-result-actions">
                          <button type="button" className="secondary" aria-label={`${policy.title} 자세히 보기`} onClick={() => setSelectedPolicy(policy)}>자세히 보기</button>
                          {isCalculablePolicy(policy) && currentPlanVersionId && <button type="button" className="primary" onClick={() => { setSelectedPolicy(policy); setScenarioInput(true); }}>내 계획에 미리 적용해보기</button>}
                        </div>
                      </article>
                    ))}
                  </div>
                  {search.results.length === 0 && <div className="tool-empty-state"><strong>조건에 맞는 정책을 찾지 못했어요.</strong><p>지원 목표를 바꿔 다시 확인해 주세요.</p></div>}
                </div>
              </section>
            )}
            {selectedPolicy && (
              <section className="policy-route-step policy-detail" data-active={!scenarioInput && !benefitInput}>
                <span aria-hidden="true">3</span>
                <div>
                  <button type="button" className="text-button policy-back" onClick={() => { setSelectedPolicy(null); setScenarioInput(false); setBenefitInput(false); setScenario(null); }}>← 추천 목록</button>
                  <section className="policy-ai-summary"><span aria-hidden="true">✦</span><div><strong>Odyssey AI</strong><p>이 정책에서 확인할 핵심 조건을 정리했어요.</p></div></section>
                  <div className="policy-detail-title"><span>추천 정책 상세</span><h3>{selectedPolicy.title}</h3><p>{selectedPolicy.summary}</p></div>
                  <section className="policy-detail-block"><h4>핵심 지원</h4><ul className="policy-support-list">{policyTextSegments(selectedPolicy.supportDetails).map((segment, index) => <li key={`${index}-${segment}`}>{segment}</li>)}</ul><p>{selectedPolicy.planConnection}</p></section>
                  {(selectedPolicy.confirmedConditions.length > 0 || selectedPolicy.additionalChecks.length > 0) ? <div className="policy-detail-grid">
                    {selectedPolicy.confirmedConditions.length > 0 && <section><h4>내 조건과 비교</h4><ul>{selectedPolicy.confirmedConditions.map((condition) => <li key={condition}><span aria-hidden="true">✓</span>{condition}</li>)}</ul></section>}
                    {selectedPolicy.additionalChecks.length > 0 && <section><h4>직접 확인 필요</h4><ul>{selectedPolicy.additionalChecks.map((condition) => <li key={condition}><span aria-hidden="true">?</span>{condition}</li>)}</ul></section>}
                  </div> : <p className="policy-empty-checks">이 정책은 일부 자격요건을 공식 페이지에서 직접 확인해야 합니다.</p>}
                  <section className="policy-source-card" aria-label="신청정보"><h4>신청정보</h4><div><span>신청 기간</span><strong>{selectedPolicy.applicationPeriod}</strong></div><div><span>신청기관</span><strong>{selectedPolicy.source.organization}</strong></div><a aria-label={`${selectedPolicy.source.organization} 공식 원문`} href={selectedPolicy.source.officialUrl} target="_blank" rel="noreferrer">공식 정보 확인 ↗</a></section>
                  <p className="policy-institution-note">실제 지원 여부와 금액은 신청기관이 확정합니다.</p>
                  {isCalculablePolicy(selectedPolicy) && currentPlanVersionId && <div className="policy-detail-actions"><div><button className="primary" onClick={() => setScenarioInput(true)}>내 계획에 미리 적용해보기</button><small>계획에 저장되지 않는 가상 계산이에요.</small></div><div><button className="secondary" onClick={() => setBenefitInput(true)}>실제 지원이 확정됐어요</button><small>기관에서 지원이 확정된 경우에만 진행해 주세요.</small></div></div>}
                </div>
              </section>
            )}
            {selectedPolicy && scenarioInput && (
              <section className="policy-route-step policy-preview-step" data-active><span aria-hidden="true">4</span><div><h3>내 계획에 미리 적용해보기</h3><p className="policy-institution-note">아직 계획에 저장되지 않는 가상 계산입니다.</p><div className="policy-scenario-form"><label>예상 지원 금액<input type="number" min="1" value={scenarioForm.amountWon} onChange={(event) => setScenarioForm({ ...scenarioForm, amountWon: event.target.value })} /></label><label>적용 시작 월<input type="month" value={scenarioForm.startYearMonth} onChange={(event) => setScenarioForm({ ...scenarioForm, startYearMonth: event.target.value })} /></label>{selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION" && <label>종료 월<input type="month" value={scenarioForm.endYearMonth} onChange={(event) => setScenarioForm({ ...scenarioForm, endYearMonth: event.target.value })} /></label>}</div><button className="primary" disabled={loading} onClick={() => void compare()}>현재 계획과 비교</button>{scenario && <div className="policy-comparison" role="status"><h4>내 계획에 미치는 영향</h4><div className="policy-comparison-cards"><section><span>현재 계획</span><strong>{formatMoneyCompact(scenario.currentPlanSummary.recommendedMonthlySpending)}</strong><small>월 유동지출 · 안정성 {percent.format(scenario.currentPlanSummary.simulationCoverage)}</small></section><span aria-hidden="true">→</span><section className="assumed"><span>지원 적용 가정</span><strong>{formatMoneyCompact(scenario.assumedPlanSummary.recommendedMonthlySpending)}</strong><small>월 유동지출 · 안정성 {percent.format(scenario.assumedPlanSummary.simulationCoverage)}</small></section></div><p>{scenario.assumptionNotice}</p></div>}</div></section>
            )}
            {selectedPolicy && benefitInput && (selectedPolicy.calculationMode === "ONE_TIME_FUNDING" || selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION") && (
              <section className="policy-route-step policy-confirm-step" data-active><span aria-hidden="true">5</span><div><h3>실제 지원이 확정됐나요?</h3><p className="policy-institution-note">기관에서 확정된 지원 내용만 입력해 주세요. 저장하면 지원 내용을 반영한 새 계획을 준비합니다.</p><label className="policy-confirm-check"><input type="checkbox" checked={benefitForm.institutionConfirmed} onChange={(event) => setBenefitForm({ ...benefitForm, institutionConfirmed: event.target.checked })} />신청기관에서 실제 지원이 확정되었음을 확인했어요.</label><div className="policy-scenario-form"><label>지원 금액<input type="number" min="1" value={benefitForm.amountWon} onChange={(event) => setBenefitForm({ ...benefitForm, amountWon: event.target.value })} /></label><label>시작 월<input type="month" value={benefitForm.startYearMonth} onChange={(event) => setBenefitForm({ ...benefitForm, startYearMonth: event.target.value })} /></label>{selectedPolicy.calculationMode === "MONTHLY_EXPENSE_REDUCTION" && <label>종료 월<input type="month" value={benefitForm.endYearMonth} onChange={(event) => setBenefitForm({ ...benefitForm, endYearMonth: event.target.value })} /></label>}</div><button className="primary" disabled={loading} onClick={() => void confirmBenefit()}>{loading ? "저장하고 재계획 중…" : "확정 지원 저장"}</button></div></section>
            )}
            <section className="confirmed-benefits">
              <h3>확정된 지원 내역</h3>
              {benefitsLoading && <p role="status">지원 내역을 불러오고 있습니다.</p>}
              {!benefitsLoading && benefits.length === 0 && <p>확정된 지원 내역이 없습니다.</p>}
              {benefits.map((benefit) => <article key={benefit.id}><div><strong>{benefit.adjustmentType === "ONE_TIME_FUNDING" ? "일시 지원" : "월 지출 지원"}</strong><span>{formatMoneyCompact(benefit.amountWon)} · {benefit.startYearMonth}{benefit.endYearMonth ? ` ~ ${benefit.endYearMonth}` : ""}</span></div><button className="text-button" disabled={!api.delete} onClick={() => void cancelBenefit(benefit.id)}>지원 취소</button></article>)}
            </section>
          </div>
        )}
      </dialog>
      <SavingsRecommendationDialog api={api} open={savingsOpen} onClose={() => setSavingsOpen(false)} />
      <AiAssistantDrawer
        api={api}
        open={assistantOpen}
        context={assistantContext}
        showPlanOverview={assistantPlanOverview}
        onClose={() => setAssistantOpen(false)}
        onOpenPolicy={() => void open("policy", null)}
        onOpenTransactions={onOpenTransactions ?? (() => undefined)}
        onRequestReplan={onRequestReplan ? () => onRequestReplan(true) : async () => false}
      />
    </div>
  );
};
