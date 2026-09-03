import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, type ApiClient } from "./api";
import { sidoRegions, sigunguRegions } from "./data/regionCodes";
import { formatMoneyCompact } from "./formatMoney";
import {
  parseFinancialProfile,
  parseGoalDetail,
  parseScheduledExpense,
  parseScheduledExpenses,
  parseUserProfile,
  type Dashboard,
  type PlanOption,
  type PlanVersion,
  type ScheduledExpense,
} from "./types";

const percent = new Intl.NumberFormat("ko-KR", { style: "percent", maximumFractionDigits: 0 });

type EditableValues = {
  birthDate: string;
  regionCode: string;
  monthlyIncome: string;
  monthlyFixedCost: string;
  name: string;
  targetAmount: string;
  currentSavedAmount: string;
  targetDate: string;
};

const emptyValues: EditableValues = {
  birthDate: "",
  regionCode: "",
  monthlyIncome: "",
  monthlyFixedCost: "",
  name: "",
  targetAmount: "",
  currentSavedAmount: "",
  targetDate: "",
};

const errorMessage = (reason: unknown, fallback: string) => {
  if (!(reason instanceof ApiError)) return fallback;
  if (reason.code === "PLAN_INFEASIBLE") return "목표 달성이 불가능한 계획입니다";
  if (reason.status === 422) return "입력값을 확인해 주세요.";
  if (reason.status === 409) return "다른 변경이 반영되었습니다. 최신 상태를 다시 불러와 주세요.";
  return reason.message || fallback;
};

const numericValue = (value: string, label: string, minimum: number) => {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum) throw new Error(`${label}을 확인해 주세요.`);
  return parsed;
};

export const PlanEditor = ({
  api,
  goalId,
  onCancel,
  onSaved,
}: {
  api: Pick<ApiClient, "get" | "post"> & Partial<Pick<ApiClient, "put" | "patch">>;
  goalId: number;
  onCancel: () => void;
  onSaved: (needsReplan: boolean) => Promise<void>;
}) => {
  const [initial, setInitial] = useState<EditableValues | null>(null);
  const [values, setValues] = useState<EditableValues>(emptyValues);
  const [scheduled, setScheduled] = useState<ScheduledExpense[]>([]);
  const [scheduleInput, setScheduleInput] = useState({ name: "", amount: "", scheduledDate: "" });
  const [editingExpense, setEditingExpense] = useState<{ id: number; name: string; amount: string; scheduledDate: string } | null>(null);
  const [addingExpense, setAddingExpense] = useState(false);
  const [scheduledExpenseChanged, setScheduledExpenseChanged] = useState(false);
  const [scheduleBusy, setScheduleBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    const load = async () => {
      setLoading(true);
      setError("");
      try {
        const [profileResponse, financialResponse, goalResponse, expensesResponse] = await Promise.all([
          api.get("/me/profile", parseUserProfile),
          api.get("/me/financial-profile", parseFinancialProfile),
          api.get(`/goals/${goalId}`, parseGoalDetail),
          api.get("/scheduled-expenses?status=PLANNED", parseScheduledExpenses),
        ]);
        if (!mounted.current) return;
        const profile = parseUserProfile(profileResponse);
        const financial = parseFinancialProfile(financialResponse);
        const goal = parseGoalDetail(goalResponse);
        const expenses = parseScheduledExpenses(expensesResponse);
        const loaded = {
          birthDate: profile.birthDate ?? "",
          regionCode: profile.regionCode ?? "",
          monthlyIncome: String(financial.monthlyIncome),
          monthlyFixedCost: String(financial.monthlyFixedCost),
          name: goal.name,
          targetAmount: String(goal.targetAmount),
          currentSavedAmount: String(goal.currentSavedAmount),
          targetDate: goal.targetDate,
        };
        setInitial(loaded);
        setValues(loaded);
        setScheduled(expenses.filter((expense) => expense.status === "PLANNED"));
      } catch (reason) {
        if (mounted.current) setError(errorMessage(reason, "계획 정보를 불러오지 못했습니다."));
      } finally {
        if (mounted.current) setLoading(false);
      }
    };
    void load();
    return () => {
      mounted.current = false;
    };
  }, [api, goalId]);

  const selectedRegion = sigunguRegions.find((region) => region.code === values.regionCode);
  const selectedSido = selectedRegion?.sidoCode ?? "";
  const fieldChanged = initial !== null && Object.keys(values).some(
    (key) => values[key as keyof EditableValues] !== initial[key as keyof EditableValues],
  );
  const changed = fieldChanged || scheduledExpenseChanged;
  const planFieldChanged = initial !== null && [
    "monthlyIncome",
    "monthlyFixedCost",
    "targetAmount",
    "currentSavedAmount",
    "targetDate",
  ].some((key) => values[key as keyof EditableValues] !== initial[key as keyof EditableValues]);
  const needsReplan = planFieldChanged || scheduledExpenseChanged;

  const set = (key: keyof EditableValues, value: string) =>
    setValues((current) => ({ ...current, [key]: value }));

  const createExpense = async (event: FormEvent) => {
    event.preventDefault();
    if (scheduleBusy || saving) return;
    setScheduleBusy(true);
    setError("");
    setStatus("");
    try {
      if (!scheduleInput.name.trim() || !scheduleInput.scheduledDate) throw new Error("예정지출 이름과 날짜를 확인해 주세요.");
      const created = parseScheduledExpense(await api.post(
        "/scheduled-expenses",
        {
          name: scheduleInput.name.trim(),
          amount: numericValue(scheduleInput.amount, "예정지출 금액", 1),
          scheduledDate: scheduleInput.scheduledDate,
        },
        parseScheduledExpense,
      ));
      setScheduled((current) => [...current, created]);
      setScheduleInput({ name: "", amount: "", scheduledDate: "" });
      setAddingExpense(false);
      setScheduledExpenseChanged(true);
      setStatus("예정지출을 추가했습니다. 변경사항 저장 및 재계산을 완료해 주세요.");
    } catch (reason) {
      setError(reason instanceof Error && !(reason instanceof ApiError) ? reason.message : errorMessage(reason, "예정지출을 추가하지 못했습니다."));
    } finally {
      setScheduleBusy(false);
    }
  };

  const updateExpense = async (event: FormEvent) => {
    event.preventDefault();
    if (!editingExpense || scheduleBusy || saving || !api.patch) return;
    setScheduleBusy(true);
    setError("");
    setStatus("");
    try {
      if (!editingExpense.name.trim() || !editingExpense.scheduledDate) throw new Error("예정지출 이름과 날짜를 확인해 주세요.");
      const updated = parseScheduledExpense(await api.patch(
        `/scheduled-expenses/${editingExpense.id}`,
        {
          name: editingExpense.name.trim(),
          amount: numericValue(editingExpense.amount, "예정지출 금액", 1),
          scheduledDate: editingExpense.scheduledDate,
        },
        parseScheduledExpense,
      ));
      setScheduled((current) => current.map((expense) => expense.id === updated.id ? updated : expense));
      setEditingExpense(null);
      setScheduledExpenseChanged(true);
      setStatus("예정지출을 수정했습니다. 변경사항 저장 및 재계산을 완료해 주세요.");
    } catch (reason) {
      setError(reason instanceof Error && !(reason instanceof ApiError) ? reason.message : errorMessage(reason, "예정지출을 수정하지 못했습니다."));
    } finally {
      setScheduleBusy(false);
    }
  };

  const cancelExpense = async (expense: ScheduledExpense) => {
    if (scheduleBusy || saving || !api.patch) return;
    setScheduleBusy(true);
    setError("");
    setStatus("");
    try {
      await api.patch(`/scheduled-expenses/${expense.id}`, { status: "CANCELLED" }, parseScheduledExpense);
      setScheduled((current) => current.filter((item) => item.id !== expense.id));
      setEditingExpense((current) => current?.id === expense.id ? null : current);
      setScheduledExpenseChanged(true);
      setStatus("예정지출을 삭제했습니다. 변경사항 저장 및 재계산을 완료해 주세요.");
    } catch (reason) {
      setError(errorMessage(reason, "예정지출을 삭제하지 못했습니다."));
    } finally {
      setScheduleBusy(false);
    }
  };

  const save = async () => {
    if (!initial || saving || scheduleBusy || !changed) return;
    if (!api.put || !api.patch) {
      setError("정보 수정 기능을 사용할 수 없습니다.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      const profileChanged = values.birthDate !== initial.birthDate || values.regionCode !== initial.regionCode;
      const financialChanged = values.monthlyIncome !== initial.monthlyIncome || values.monthlyFixedCost !== initial.monthlyFixedCost;
      const goalChanged = ["name", "targetAmount", "currentSavedAmount", "targetDate"].some(
        (key) => values[key as keyof EditableValues] !== initial[key as keyof EditableValues],
      );

      if (profileChanged) {
        if (!values.birthDate || !values.regionCode) throw new Error("생년월일과 지역을 확인해 주세요.");
        await api.put(
          "/me/profile",
          { birthDate: values.birthDate, regionCode: values.regionCode },
          parseUserProfile,
        );
      }
      if (financialChanged) {
        await api.put(
          "/me/financial-profile",
          {
            monthlyIncome: numericValue(values.monthlyIncome, "월 소득", 0),
            monthlyFixedCost: numericValue(values.monthlyFixedCost, "월 고정비", 0),
          },
          parseFinancialProfile,
        );
      }
      if (goalChanged) {
        if (!values.name.trim() || !values.targetDate) throw new Error("목표 이름과 날짜를 확인해 주세요.");
        await api.patch(
          `/goals/${goalId}`,
          {
            name: values.name.trim(),
            targetAmount: numericValue(values.targetAmount, "목표 금액", 1),
            currentSavedAmount: numericValue(values.currentSavedAmount, "현재 모은 금액", 0),
            targetDate: values.targetDate,
          },
          parseGoalDetail,
        );
      }
      await onSaved(needsReplan);
    } catch (reason) {
      setError(reason instanceof Error && !(reason instanceof ApiError)
        ? reason.message
        : errorMessage(reason, "변경사항을 저장하지 못했습니다."));
      setSaving(false);
    }
  };

  if (loading) return <main className="plan-workflow-status" aria-busy="true"><p>계획 정보를 불러오고 있습니다.</p></main>;

  return (
    <main className="plan-edit-screen">
      <header className="plan-workflow-header">
        <h1>나의 계획 정보</h1>
        <p>변경된 항목이 있으면 계획을 다시 계산합니다.</p>
      </header>
      {error && <p className="notice danger" role="alert">{error}</p>}
      {status && <p className="notice" role="status">{status}</p>}
      {!initial ? (
        <section className="plan-workflow-status"><p>계획 정보를 표시할 수 없습니다.</p><button className="secondary" onClick={onCancel}>대시보드로</button></section>
      ) : (
        <div>
          <div className="plan-edit-grid">
            <section className="plan-edit-card">
              <h2>목표와 재무 정보</h2>
              <div className="plan-edit-card-body">
                <p className="plan-edit-kicker">금융 목표</p>
                <label>목표 이름<input required value={values.name} onChange={(event) => set("name", event.target.value)} /></label>
                <div className="plan-edit-fields two-columns">
                  <label>목표 금액<span className="currency-field"><input required type="number" min="1" value={values.targetAmount} onChange={(event) => set("targetAmount", event.target.value)} /><small>원</small></span></label>
                  <label>현재 모은 금액<span className="currency-field"><input required type="number" min="0" value={values.currentSavedAmount} onChange={(event) => set("currentSavedAmount", event.target.value)} /><small>원</small></span></label>
                </div>
                <label>목표 날짜<input required type="date" value={values.targetDate} onChange={(event) => set("targetDate", event.target.value)} /></label>
                <div className="plan-edit-divider">
                  <p className="plan-edit-kicker">매달 들어오고 나가는 돈</p>
                  <div className="plan-edit-fields two-columns">
                    <label>월 소득<span className="currency-field"><input required type="number" min="0" value={values.monthlyIncome} onChange={(event) => set("monthlyIncome", event.target.value)} /><small>원</small></span><em>세후 기준</em></label>
                    <label>월 고정비<span className="currency-field"><input required type="number" min="0" value={values.monthlyFixedCost} onChange={(event) => set("monthlyFixedCost", event.target.value)} /><small>원</small></span></label>
                  </div>
                </div>
              </div>
            </section>

            <section className="plan-edit-card">
              <h2>기본 정보와 예정지출</h2>
              <div className="plan-edit-card-body">
                <div className="plan-edit-fields two-columns">
                  <label>생년월일<input required type="date" value={values.birthDate} onChange={(event) => set("birthDate", event.target.value)} /></label>
                  <label>시도<select required value={selectedSido} onChange={(event) => {
                    const first = sigunguRegions.find((region) => region.sidoCode === event.target.value);
                    set("regionCode", first?.code ?? "");
                  }}><option value="">시도 선택</option>{sidoRegions.map((region) => <option key={region.code} value={region.code}>{region.name}</option>)}</select></label>
                </div>
                <label>시군구<select required disabled={!selectedSido} value={values.regionCode} onChange={(event) => set("regionCode", event.target.value)}><option value="">시군구 선택</option>{sigunguRegions.filter((region) => region.sidoCode === selectedSido).map((region) => <option key={region.code} value={region.code}>{region.name}</option>)}</select></label>
                <div className="plan-edit-divider">
                  <p className="plan-edit-kicker">예정 지출</p>
                  <div className="plan-edit-expenses">
                    {scheduled.length === 0 && <p className="plan-edit-empty">등록된 예정 지출이 없습니다.</p>}
                    {scheduled.map((expense) => (
                      editingExpense?.id === expense.id ? (
                        <form key={expense.id} className="plan-edit-expense-form" onSubmit={(event) => void updateExpense(event)}>
                          <label>이름<input required value={editingExpense.name} onChange={(event) => setEditingExpense({ ...editingExpense, name: event.target.value })} /></label>
                          <label>금액<input required type="number" min="1" value={editingExpense.amount} onChange={(event) => setEditingExpense({ ...editingExpense, amount: event.target.value })} /></label>
                          <label>날짜<input required type="date" value={editingExpense.scheduledDate} onChange={(event) => setEditingExpense({ ...editingExpense, scheduledDate: event.target.value })} /></label>
                          <div><button type="button" className="text-button" disabled={saving} onClick={() => setEditingExpense(null)}>취소</button><button className="secondary" disabled={scheduleBusy || saving}>저장</button></div>
                        </form>
                      ) : (
                        <div key={expense.id} className="plan-edit-expense">
                          <span className="plan-edit-expense-icon" aria-hidden="true">◇</span>
                          <div><strong>{expense.name}</strong><time dateTime={expense.scheduledDate}>{expense.scheduledDate.replace(/-/g, ".")}</time></div>
                          <b>{formatMoneyCompact(expense.amount)}</b>
                          <span className="plan-edit-expense-actions"><button type="button" disabled={scheduleBusy || saving} onClick={() => setEditingExpense({ id: expense.id, name: expense.name, amount: String(expense.amount), scheduledDate: expense.scheduledDate })}>수정</button><button type="button" disabled={scheduleBusy || saving} onClick={() => void cancelExpense(expense)}>삭제</button></span>
                        </div>
                      )
                    ))}
                  </div>
                  {addingExpense ? (
                    <form className="plan-edit-expense-form" onSubmit={(event) => void createExpense(event)}>
                      <label>이름<input required value={scheduleInput.name} onChange={(event) => setScheduleInput({ ...scheduleInput, name: event.target.value })} /></label>
                      <label>금액<input required type="number" min="1" value={scheduleInput.amount} onChange={(event) => setScheduleInput({ ...scheduleInput, amount: event.target.value })} /></label>
                      <label>날짜<input required type="date" value={scheduleInput.scheduledDate} onChange={(event) => setScheduleInput({ ...scheduleInput, scheduledDate: event.target.value })} /></label>
                      <div><button type="button" className="text-button" disabled={saving} onClick={() => setAddingExpense(false)}>취소</button><button className="secondary" disabled={scheduleBusy || saving}>추가</button></div>
                    </form>
                  ) : (
                    <button type="button" className="plan-edit-add-expense" disabled={saving} onClick={() => setAddingExpense(true)}>＋ 예정 지출 추가</button>
                  )}
                </div>
              </div>
            </section>
          </div>

          {changed ? (
            <section className={`plan-edit-notice ${needsReplan ? "warning" : "info"}`}>
              <div>
                <strong>{needsReplan ? "이 변경은 현재 계획에 영향을 줍니다." : "이 변경은 계획을 다시 계산하지 않습니다."}</strong>
                <p>{needsReplan ? "모든 변경사항을 저장한 뒤 최종 계획을 한 번 더 계산합니다." : "변경사항을 저장하고 대시보드로 돌아갑니다."}</p>
              </div>
              <div className="plan-workflow-actions"><button type="button" className="text-button" disabled={saving || scheduleBusy} onClick={onCancel}>취소</button><button type="button" className="primary" disabled={saving || scheduleBusy} onClick={() => void save()}>{saving ? "저장 중…" : needsReplan ? "변경사항 저장 및 재계산" : "변경사항 저장"}</button></div>
            </section>
          ) : (
            <div className="plan-edit-back"><button type="button" className="text-button" onClick={onCancel}>← 대시보드로</button></div>
          )}
        </div>
      )}
    </main>
  );
};

const optionTitle = (option: PlanOption) => {
  if (option.nominalLevel === 0.7) return "부담 완화형";
  if (option.nominalLevel === 0.8) return "균형 회복형";
  if (option.nominalLevel === 0.9) return "목표 우선형";
  return "맞춤 계획";
};

const optionLabel = (option: PlanOption, index: number) =>
  option.nominalLevel === null ? "CUSTOM" : `PLAN ${String.fromCharCode(65 + index)}`;

const differenceCopy = (option: PlanOption, current: PlanOption | null) => {
  if (!current) return "현재 조건에 맞춰 다시 계산한 계획입니다.";
  const spending = current.recommendedMonthlySpending - option.recommendedMonthlySpending;
  const stability = Math.round((option.simulationCoverage - current.simulationCoverage) * 100);
  const spendingCopy = spending === 0
    ? "현재와 같은 월 지출"
    : `현재보다 월 ${formatMoneyCompact(Math.abs(spending))} ${spending > 0 ? "적게" : "많게"}`;
  const stabilityCopy = stability === 0
    ? "안정성 동일"
    : `안정성 ${Math.abs(stability)}%p ${stability > 0 ? "높음" : "낮음"}`;
  return `${spendingCopy} · ${stabilityCopy}`;
};

export const ReplanComparison = ({
  dashboard,
  proposal,
  busy,
  error,
  onKeep,
  onApply,
}: {
  dashboard: Dashboard;
  proposal: PlanVersion;
  busy: boolean;
  error: string;
  onKeep: () => Promise<void>;
  onApply: (optionId: number) => Promise<void>;
}) => {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const options = proposal.options.filter((option) => option.optionType === "PRESET");
  const selected = options.find((option) => option.id === selectedId) ?? null;
  const current = dashboard.selectedOption;
  const goal = dashboard.goal;

  return (
    <main className="replan-screen">
      <header className="plan-workflow-header">
        <h1>새 계획을 선택하거나 현재 계획을 유지하세요</h1>
        <p>최근 소비를 반영한 새 계획과 현재 계획의 차이를 비교해보세요.</p>
      </header>
      {error && <p className="notice danger" role="alert">{error}</p>}
      <section className="replan-current-card">
        <div className="replan-current-heading"><div><span>현재 계획</span><b>유지 중</b></div><button className="secondary" disabled={busy} onClick={() => void onKeep()}>현재 계획 유지하기</button></div>
        <div className="replan-current-metrics">
          <div><span>월 유동지출 한도</span><strong>{current ? formatMoneyCompact(current.recommendedMonthlySpending) : "자료 없음"}</strong></div>
          <div><span>계획 안정성</span><strong className="stability">{current ? percent.format(current.simulationCoverage) : "자료 없음"}</strong></div>
          <div><span>이번 달 이미 사용</span><strong>{dashboard.monthProgress ? formatMoneyCompact(dashboard.monthProgress.actualToDate) : "자료 없음"}</strong></div>
          <div><span>목표까지</span><strong>{goal ? `${goal.remainingMonths}개월` : "자료 없음"}</strong></div>
        </div>
      </section>
      <div className="replan-separator"><span>새로운 계획 제안</span></div>
      <section className="replan-options" aria-label="새로운 계획 제안">
        {options.map((option, index) => {
          const selectedOption = selectedId === option.id;
          return (
            <button key={option.id} type="button" aria-pressed={selectedOption} className={`replan-option${selectedOption ? " selected" : ""}`} onClick={() => setSelectedId(option.id)}>
              <div className="replan-option-title"><div><h2>{optionTitle(option)}</h2><p>{differenceCopy(option, current)}</p></div><span>{selectedOption ? "✓" : optionLabel(option, index)}</span></div>
              <p className="replan-option-label">월 유동지출</p>
              <strong className="replan-option-amount">{formatMoneyCompact(option.recommendedMonthlySpending)}</strong>
              <div className="replan-option-coverage"><span>계획 안정성</span><strong>{percent.format(option.simulationCoverage)}</strong></div>
              <div className="replan-option-progress"><span style={{ width: percent.format(option.simulationCoverage) }} /></div>
              {option.aggressiveWarning && <p className="replan-option-warning" role="alert">현재 평균 소비보다 상당히 낮아 지속하기 어려울 수 있어요.</p>}
            </button>
          );
        })}
      </section>
      {selected && (
        <section className="replan-apply-bar">
          <div><strong>{optionTitle(selected)} 선택됨</strong><p>월 {formatMoneyCompact(selected.recommendedMonthlySpending)} · 계획 안정성 {percent.format(selected.simulationCoverage)}</p></div>
          <div><p>이번 달 남은 권장 소비: <strong>{dashboard.monthProgress ? formatMoneyCompact(Math.max(selected.recommendedMonthlySpending - dashboard.monthProgress.actualToDate, 0)) : "자료 없음"}</strong></p><button className="primary" disabled={busy} onClick={() => void onApply(selected.id)}>{busy ? "적용 중…" : "이 계획으로 변경하기"}</button></div>
        </section>
      )}
      <button className="text-button replan-back" disabled={busy} onClick={() => void onKeep()}>← 대시보드로</button>
    </main>
  );
};
