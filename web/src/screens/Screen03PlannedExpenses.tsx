// Screen 3 — Planned Expenses
import { useState } from "react";
import { Button, ProgressStepper, SectionCard, NoticeCard, Input, CurrencyInput, OnboardingLayout } from "../components/shared";

type Expense = { id: number; name: string; date: string; amount: number };
const INITIAL: Expense[] = [
  { id: 1, name: "일본 여행",  date: "2026.11.15", amount: 1200000 },
  { id: 2, name: "노트북 구매", date: "2027.02.10", amount: 1500000 },
];

export default function Screen03PlannedExpenses({ onNext, onPrev }: { onNext: () => void; onPrev: () => void }) {
  const [items,    setItems]    = useState<Expense[]>(INITIAL);
  const [adding,   setAdding]   = useState(false);
  const [newName,  setNewName]  = useState("");
  const [newDate,  setNewDate]  = useState("");
  const [newAmt,   setNewAmt]   = useState("");

  const total       = items.reduce((s, e) => s + e.amount, 0);
  const BUDGET      = 22000000; // hypothetical ceiling for demo
  const impossible  = total > BUDGET;

  function addExpense() {
    if (!newName || !newDate || !newAmt) return;
    const formatted = new Date(newDate).toLocaleDateString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" }).replace(/\. /g, ".").replace(/\.$/, "");
    setItems([...items, { id: Date.now(), name: newName, date: formatted, amount: Number(newAmt) }]);
    setNewName(""); setNewDate(""); setNewAmt(""); setAdding(false);
  }

  return (
    <OnboardingLayout>
      {/* Stepper */}
      <div className="mb-10 max-w-[480px] mx-auto">
        <ProgressStepper steps={["목표 설정", "예정지출", "마이데이터", "계획 생성"]} current={1}/>
      </div>

      <div className="mb-8">
        <h1 className="text-[30px] font-bold text-[#111827] tracking-tight">미리 알고 있는 큰 지출이 있나요?</h1>
        <p className="text-[15px] text-[#6B7280] mt-1.5">
          여행, 전자기기 구매, 등록금처럼 예정된 지출을 알려주면 계획에 미리 반영할 수 있어요.
        </p>
      </div>

      <div className="flex flex-col gap-5">
        <SectionCard>
          <div className="flex flex-col gap-3">
            <div className="mb-1 flex items-center justify-between px-1">
              <div>
                <p className="text-[15px] font-bold text-[#111827]">등록한 예정지출</p>
                <p className="mt-0.5 text-[12px] text-[#7C8594]">현재 {items.length}건이 계획에 반영돼요.</p>
              </div>
            </div>

            {items.length === 0 && !adding && (
              <div className="text-center py-8">
                <div className="w-12 h-12 rounded-2xl bg-[#F5F6F9] flex items-center justify-center mx-auto mb-3">
                  <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
                    <rect x="2" y="5" width="18" height="15" rx="3" stroke="#9CA3AF" strokeWidth="1.5"/>
                    <path d="M2 10h18M7 2v3M15 2v3" stroke="#9CA3AF" strokeWidth="1.5" strokeLinecap="round"/>
                  </svg>
                </div>
                <p className="text-[14px] text-[#9CA3AF]">아직 예정된 큰 지출이 없어요.</p>
              </div>
            )}

            {items.map((exp) => (
              <div key={exp.id} className="flex items-center gap-3.5 rounded-[14px] border border-transparent bg-[#F8F9FB] px-4 py-3.5 transition-colors hover:border-[#E8EAEF]">
                <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-[10px] bg-[#EEF0FF]">
                  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <rect x="1.5" y="3" width="13" height="11.5" rx="2.5" stroke="#4F58FF" strokeWidth="1.4"/>
                    <path d="M1.5 7h13M5 1.5v3M11 1.5v3" stroke="#4F58FF" strokeWidth="1.4" strokeLinecap="round"/>
                  </svg>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[14px] font-semibold text-[#111827]">{exp.name}</p>
                  <p className="mt-0.5 text-[12px] text-[#7C8594]">{exp.date}</p>
                </div>
                <span className="num flex-shrink-0 text-[14px] font-bold text-[#111827]">{exp.amount.toLocaleString("ko-KR")}원</span>
                <button
                  onClick={() => setItems(items.filter((e) => e.id !== exp.id))}
                  aria-label={`${exp.name} 삭제`}
                  className="flex h-8 w-8 flex-shrink-0 cursor-pointer items-center justify-center rounded-[9px] text-[#9CA3AF] transition-colors hover:bg-[#FFF0F0] hover:text-[#E53E3E] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F58FF]"
                >
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                    <path d="M2.5 3.5h9M5 1.75h4M4 5.25v5.5M7 5.25v5.5M10 5.25v5.5M3.5 3.5l.5 8.25h6l.5-8.25" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </button>
              </div>
            ))}

            {/* Add form */}
            {adding ? (
              <div className="border-2 border-dashed border-[#C7CAFF] bg-[#F7F8FF] rounded-[14px] p-5">
                <p className="text-[13px] font-bold text-[#4F58FF] mb-4">예정 지출 추가</p>
                <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <Input
                    label="항목명"
                    placeholder="일본 여행"
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    className="h-12"
                  />
                  <Input
                    label="예정 날짜"
                    type="date"
                    value={newDate}
                    onChange={(e) => setNewDate(e.target.value)}
                    className="h-12"
                  />
                  <CurrencyInput label="금액" value={newAmt} onChange={setNewAmt} className="num h-12"/>
                </div>
                <div className="flex gap-2 justify-end">
                  <Button variant="ghost" size="sm" onClick={() => setAdding(false)}>취소</Button>
                  <Button size="sm" onClick={addExpense} disabled={!newName || !newDate || !newAmt}>추가하기</Button>
                </div>
              </div>
            ) : (
              <button
                onClick={() => setAdding(true)}
                className="flex items-center gap-2 text-[13px] font-semibold text-[#4F58FF] py-3.5 px-4 rounded-[12px] border-2 border-dashed border-[#C7CAFF] hover:bg-[#EEF0FF] transition-all cursor-pointer"
              >
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <path d="M8 3v10M3 8h10" stroke="#4F58FF" strokeWidth="2" strokeLinecap="round"/>
                </svg>
                예정지출 추가
              </button>
            )}

            {/* Total */}
            {items.length > 0 && (
              <div className="mt-1 flex items-center justify-between rounded-[14px] border border-[#C7CAFF] bg-[#EEF0FF] px-4 py-3.5">
                <span className="text-[12px] font-semibold text-[#5961A8]">예정 지출 합계</span>
                <div className="text-right">
                  <span className="num text-[20px] font-bold tracking-tight text-[#3840E0]">{Math.round(total / 10000).toLocaleString("ko-KR")}만원</span>
                </div>
              </div>
            )}
          </div>
        </SectionCard>

        {/* Error state */}
        {impossible && (
          <NoticeCard
            type="error"
            title="이 예정지출을 포함하면 현재 목표를 달성하기 어려워요."
            description="예정 지출 합계가 목표 달성에 필요한 저축액을 초과합니다."
            actions={
              <>
                <Button variant="danger" size="sm">예정지출 수정</Button>
                <Button variant="secondary" size="sm">목표 다시 설정</Button>
              </>
            }
          />
        )}

        {/* Tip */}
        {!impossible && (
          <div className="flex items-start gap-3 px-1">
            <div className="w-5 h-5 rounded-full bg-[#EEF0FF] flex items-center justify-center flex-shrink-0 mt-0.5">
              <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                <circle cx="5" cy="5" r="4" stroke="#4F58FF" strokeWidth="1.2"/>
                <path d="M5 3.5v2.5M5 7.5v.5" stroke="#4F58FF" strokeWidth="1.2" strokeLinecap="round"/>
              </svg>
            </div>
            <p className="text-[12px] leading-relaxed text-[#7C8594]">
              예정지출이 없어도 괜찮아요. 나중에 대시보드에서 언제든지 추가할 수 있습니다.
            </p>
          </div>
        )}

        <div className="flex items-center justify-between pt-2">
          <button
            onClick={onPrev}
            className="cursor-pointer rounded-[10px] px-2 py-2 text-[14px] font-semibold text-[#7C8594] transition-colors hover:bg-white hover:text-[#4F58FF]"
          >
            ← 이전
          </button>
          <Button size="lg" onClick={onNext} disabled={impossible} className="h-14 min-w-[176px] rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]">
            마이데이터 연결
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path d="M3.5 8h9M9.5 4.5l3.5 3.5-3.5 3.5" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </Button>
        </div>
      </div>
    </OnboardingLayout>
  );
}
