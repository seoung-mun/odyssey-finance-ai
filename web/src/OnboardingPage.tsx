import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";

type Mode = "OFF" | "AUTO" | "CUSTOM";

export function OnboardingPage({ api }: { api: ApiClient }) {
  const navigate = useNavigate();
  const [direct, setDirect] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [mode, setMode] = useState<Mode>("AUTO");

  async function loadSample() {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await api.post("/me/sample-data");
      navigate("/dashboard", { replace: true });
    } catch (reason) {
      const failure = reason as ApiError;
      setError(failure.status === 409 ? "이미 직접 입력한 정보가 있어 샘플을 추가할 수 없습니다." : "샘플을 불러오지 못했습니다. 다시 시도해 주세요.");
    } finally { setBusy(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    const values = new FormData(event.currentTarget);
    try {
      await api.put("/me/profile", {
        birthDate: values.get("birthDate") || undefined,
        regionCode: values.get("regionCode") || undefined,
      });
      await api.put("/me/financial-profile", {
        monthlyIncome: Number(values.get("monthlyIncome")),
        monthlyFixedCost: Number(values.get("monthlyFixedCost")),
        spendingFloorMode: mode,
        customMonthlyVariableFloor: mode === "CUSTOM" ? Number(values.get("customFloor")) : null,
      });
      const goal = await api.post<{ id: number }>("/goals", {
        name: values.get("goalName"),
        targetAmount: Number(values.get("targetAmount")),
        currentSavedAmount: Number(values.get("currentSavedAmount")),
        targetDate: values.get("targetDate"),
      });
      await api.post(`/goals/${goal.id}/plan-versions`, { generationType: "INITIAL" });
      navigate("/dashboard", { replace: true });
    } catch (reason) {
      const failure = reason as ApiError;
      if (failure.status === 422) setError("현재 조건으로는 목표에 닿기 어렵습니다. 목표 금액이나 날짜를 조정해 주세요.");
      else if (failure.status === 0 || failure.status === 503) setError("연결하지 못했습니다. 입력값은 그대로 보관했습니다. 다시 시도해 주세요.");
      else setError(failure.message || "입력 내용을 확인해 주세요.");
    } finally { setBusy(false); }
  }

  if (!direct) return (
    <main className="onboarding-shell">
      <header><p className="brand-mark">ODYSSEY / 출발점</p><h1>어떤 항로로 시작할까요?</h1><p>고정 샘플로 먼저 둘러보거나, 내 정보로 바로 계획을 만들 수 있습니다.</p></header>
      {error && <p role="alert" className="notice danger">{error}</p>}
      <section className="start-options">
        <article><p className="eyebrow">빠른 탐색</p><h2>샘플로 둘러보기</h2><p>빈 계정에만 고정 샘플을 넣습니다. 언제든 내 정보로 바꿀 수 있습니다.</p><button className="secondary" disabled={busy} onClick={() => void loadSample()}>샘플로 둘러보기</button></article>
        <article><p className="eyebrow">내 계획</p><h2>직접 항로 만들기</h2><p>월 소득과 고정비, 목표를 입력해 첫 계획을 계산합니다.</p><button className="primary" onClick={() => setDirect(true)}>직접 시작하기</button></article>
      </section>
    </main>
  );

  return (
    <main className="onboarding-shell narrow">
      <header><p className="eyebrow">출발 정보</p><h1>지금의 현실에서 시작합니다</h1><p>브라우저 검증 후 서버와 DB가 같은 규칙을 다시 확인합니다.</p></header>
      <form className="onboarding-form" onSubmit={(event) => void submit(event)}>
        <fieldset><legend>나의 기준</legend><div className="field-row"><label>생년월일<input name="birthDate" type="date" /></label><label>지역 코드<input name="regionCode" inputMode="numeric" pattern="[0-9]{5}" maxLength={5} placeholder="시군구 5자리" /></label></div></fieldset>
        <fieldset><legend>한 달의 생활</legend><div className="field-row"><label>월 소득<input name="monthlyIncome" type="number" min="0" step="1" required /></label><label>월 고정비<input name="monthlyFixedCost" type="number" min="0" step="1" required /></label></div><label>생활비 하한<select value={mode} onChange={(event) => setMode(event.target.value as Mode)}><option value="AUTO">최근 소비로 자동 설정</option><option value="CUSTOM">직접 설정</option><option value="OFF">사용하지 않음</option></select></label>{mode === "CUSTOM" && <label>최소 월 유동지출<input name="customFloor" type="number" min="0" step="1" required /></label>}</fieldset>
        <fieldset><legend>첫 번째 목적지</legend><label>목표 이름<input name="goalName" required minLength={1} maxLength={100} /></label><div className="field-row"><label>목표 금액<input name="targetAmount" type="number" min="1" step="1" required /></label><label>현재 모은 금액<input name="currentSavedAmount" type="number" min="0" step="1" defaultValue="0" required /></label></div><label>목표 날짜<input name="targetDate" type="date" required /></label></fieldset>
        {error && <p role="alert" className="notice danger">{error}</p>}
        <button className="primary full" disabled={busy}>{busy ? "계산하고 있습니다" : "계획 만들기"}</button>
      </form>
    </main>
  );
}
