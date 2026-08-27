// Screen 2 — Basic Info Onboarding
import { useState } from "react";
import { Button, ProgressStepper, Input, CurrencyInput } from "../components/shared";

// Converts raw number to Korean short-form (e.g. 30000000 → "3,000만원")
function toKorean(n: number): string {
  if (!n || n < 10000) return "";
  const man = n / 10000;
  if (man >= 10000) {
    const uk = man / 10000;
    return `${Number.isInteger(uk) ? uk : uk.toFixed(1)}억원`;
  }
  return `${Number.isInteger(man) ? man.toLocaleString("ko-KR") : Math.floor(man).toLocaleString("ko-KR")}만원`;
}

// Input + CurrencyInput with Korean amount hint shown below
function MoneyField({ label, value, onChange, hint, prefix = "" }: {
  label: string; value: string; onChange: (v: string) => void; hint?: string; prefix?: string;
}) {
  const n = Number(value) || 0;
  const short = toKorean(n);
  return (
    <div>
      <CurrencyInput label={label} value={value} onChange={onChange} hint={hint} className="num h-12"/>
      {short && (
        <p className="num text-[12px] text-[#6B7280] mt-1.5 font-medium">
          {prefix}{short}
        </p>
      )}
    </div>
  );
}

// Thin label for each form section
function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="mb-5 text-[13px] font-bold text-[#4F58FF]">
      {children}
    </p>
  );
}

const INPUT_BASE = "h-12 w-full rounded-[12px] border border-[#E8EAEF] bg-white px-3.5 text-[14px] text-[#111827] hover:border-[#C7CAFF] focus:outline-none focus:ring-2 focus:ring-[#4F58FF]/30 focus:border-[#4F58FF] transition-all";

export default function Screen02Onboarding({ onNext, onPrev }: { onNext: () => void; onPrev: () => void }) {
  const [goalName,     setGoalName]     = useState("독립자금");
  const [goalAmount,   setGoalAmount]   = useState("30000000");
  const [currentSaved, setCurrentSaved] = useState("8000000");
  const [goalDateRaw,  setGoalDateRaw]  = useState("2028-01");
  const [income,       setIncome]       = useState("3500000");
  const [fixed,        setFixed]        = useState("1200000");
  const [birthdate,    setBirthdate]    = useState("1998-05-12");
  const [region,       setRegion]       = useState("서울특별시");

  const goalN    = Number(goalAmount)   || 0;
  const savedN   = Number(currentSaved) || 0;
  const incomeN  = Number(income)       || 0;
  const fixedN   = Number(fixed)        || 0;
  const needed   = Math.max(0, goalN - savedN);

  const now     = new Date();
  const minDate = `${now.getFullYear()}-${String(now.getMonth() + 2).padStart(2, "0")}`;
  const targetY = Number(goalDateRaw.slice(0, 4)) || now.getFullYear();
  const targetM = Number(goalDateRaw.slice(5, 7)) || now.getMonth() + 1;
  const months  = Math.max(1, (targetY - now.getFullYear()) * 12 + (targetM - now.getMonth() - 1));
  const monthlyNeeded = needed > 0 ? Math.ceil(needed / months) : 0;

  const freeIncome = incomeN - fixedN;
  const impossible = incomeN > 0 && fixedN > 0 && freeIncome > 0 && monthlyNeeded > freeIncome;

  const canProceed =
    goalName.trim().length > 0 &&
    goalN > 0 &&
    goalDateRaw.length > 0 &&
    incomeN > 0 &&
    !impossible;

  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      {/* Stepper */}
      <div className="flex justify-center px-6 pb-1 pt-8">
        <ProgressStepper
          steps={["목표 설정", "예정지출", "마이데이터", "계획 생성"]}
          current={0}
        />
      </div>

      {/* Form */}
      <main className="mx-auto max-w-[1080px] px-6 pb-10 pt-7">

        {/* Page title */}
        <div className="mb-7 max-w-[640px]">
          <h1 className="mb-2 text-[30px] font-bold leading-tight tracking-[-0.025em] text-[#111827]">
            이루고 싶은 목표부터 정해볼게요
          </h1>
          <p className="text-[15px] leading-relaxed text-[#6B7280]">
            목표와 현재 상황을 입력하면 매달 필요한 금액을 계산해드려요.
          </p>
        </div>

        <div className="rounded-[20px] border border-[#E8EAEF] bg-white p-6 md:p-7">
          <div className="grid items-start md:grid-cols-[1.12fr_0.88fr]">
          {/* ── 1. 금융 목표 ─────────────────────────────────── */}
          <section className="md:pr-8">
            <SectionLabel>금융 목표</SectionLabel>
            <h2 className="-mt-2 mb-6 text-[18px] font-bold tracking-tight text-[#111827]">
              얼마를, 언제까지 모을까요?
            </h2>
            <div className="flex flex-col gap-5">
              <Input
                label="목표 이름"
                placeholder="예: 독립자금, 결혼비용, 유럽 여행"
                value={goalName}
                onChange={(e) => setGoalName(e.target.value)}
                className="h-12"
              />

              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
                <MoneyField
                  label="목표 금액"
                  value={goalAmount}
                  onChange={setGoalAmount}
                />
                <MoneyField
                  label="현재 모은 금액"
                  value={currentSaved}
                  onChange={setCurrentSaved}
                  hint="지금까지 모아둔 금액"
                />
              </div>

              <div>
                <label className="mb-1.5 block text-[13px] font-semibold text-[#374151]">목표 날짜</label>
                <input
                  type="month"
                  value={goalDateRaw}
                  min={minDate}
                  onChange={(e) => setGoalDateRaw(e.target.value)}
                  className={INPUT_BASE}
                />
                {months > 0 && (
                  <p className="mt-1.5 text-[12px] text-[#7C8594]">목표까지 {months}개월 남았어요</p>
                )}
              </div>
            </div>
          </section>

          <div className="mt-8 flex flex-col border-t border-[#E8EAEF] pt-8 md:mt-0 md:border-l md:border-t-0 md:pl-8 md:pt-0">
            {/* ── 2. 재무 상황 ─────────────────────────────────── */}
            <section className="pb-6">
              <SectionLabel>재무 상황</SectionLabel>
              <h2 className="-mt-2 mb-6 text-[18px] font-bold tracking-tight text-[#111827]">
                매달 들어오고 나가는 돈
              </h2>
              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-1 xl:grid-cols-2">
                <MoneyField
                  label="월 소득"
                  value={income}
                  onChange={setIncome}
                  hint="세후 실수령액 기준"
                  prefix="월 "
                />
                <MoneyField
                  label="월 고정지출"
                  value={fixed}
                  onChange={setFixed}
                  hint="월세·보험·구독 등"
                  prefix="월 "
                />
              </div>

              {monthlyNeeded > 0 && !impossible && (
                <div className="mt-5 flex items-end justify-between border-t border-[#E8EAEF] pt-4">
                  <p className="text-[12px] font-semibold text-[#5961A8]">목표 달성에 필요한 월 저축</p>
                  <p className="num text-[22px] font-bold tracking-tight text-[#3840E0]">
                    {toKorean(monthlyNeeded) || `${monthlyNeeded.toLocaleString("ko-KR")}원`}
                  </p>
                </div>
              )}

              {impossible && (
                <div className="mt-5 flex items-start gap-3 rounded-[12px] border border-[#FEB2B2] bg-[#FFF5F5] px-4 py-3.5">
                  <svg width="15" height="15" viewBox="0 0 15 15" fill="none" className="mt-0.5 flex-shrink-0">
                    <circle cx="7.5" cy="7.5" r="6" stroke="#E53E3E" strokeWidth="1.3"/>
                    <path d="M7.5 4.5v3.5M7.5 9.5v.5" stroke="#E53E3E" strokeWidth="1.5" strokeLinecap="round"/>
                  </svg>
                  <div>
                    <p className="text-[13px] font-semibold text-[#9B2C2C]">현재 조건으로는 목표 달성이 어려워요.</p>
                    <p className="mt-0.5 text-[12px] leading-relaxed text-[#C53030]">
                      유동지출을 0원으로 가정해도 목표일까지 저축이 부족합니다. 목표 금액이나 날짜를 조정해보세요.
                    </p>
                  </div>
                </div>
              )}
            </section>

            {/* ── 3. 기본 정보 ─────────────────────────────────── */}
            <section className="border-t border-[#E8EAEF] pt-6">
              <SectionLabel>기본 정보</SectionLabel>
              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-1 xl:grid-cols-2">
                <Input
                  label="생년월일"
                  type="date"
                  value={birthdate}
                  onChange={(e) => setBirthdate(e.target.value)}
                  className="h-12"
                />
                <div>
                  <label className="mb-1.5 block text-[13px] font-semibold text-[#374151]">지역</label>
                  <select
                    value={region}
                    onChange={(e) => setRegion(e.target.value)}
                    className={INPUT_BASE + " cursor-pointer"}
                  >
                    {["서울특별시","경기도","부산광역시","인천광역시","대구광역시",
                      "광주광역시","대전광역시","울산광역시","세종특별자치시",
                      "강원도","충청북도","충청남도","전라북도","전라남도",
                      "경상북도","경상남도","제주특별자치도"].map(r => (
                      <option key={r}>{r}</option>
                    ))}
                  </select>
                </div>
              </div>
            </section>
          </div>
          </div>
        </div>

        {/* ── CTA ─────────────────────────────────────────── */}
        <div className="mt-8 flex items-center justify-between">
          <button
            onClick={onPrev}
            className="cursor-pointer rounded-[10px] px-2 py-2 text-[14px] font-semibold text-[#7C8594] transition-colors hover:bg-white hover:text-[#4F58FF]"
          >
            ← 이전
          </button>
          <Button
            size="lg"
            onClick={onNext}
            disabled={!canProceed}
            className="h-14 min-w-[152px] rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]"
          >
            예정지출 입력
            <svg width="15" height="15" viewBox="0 0 15 15" fill="none">
              <path d="M3 7.5h9M9 4l3.5 3.5L9 11" stroke="white" strokeWidth="1.8"
                strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </Button>
        </div>
      </main>
    </div>
  );
}
