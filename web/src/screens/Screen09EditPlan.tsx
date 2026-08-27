// Screen 9 — Edit Plan Info
import { useState } from "react";
import { AppHeader, Button, SectionCard, Input, CurrencyInput, ScheduledExpenseItem, NoticeCard } from "../components/shared";

export default function Screen09EditPlan({ onNavigate }: { onNavigate: (s: number) => void }) {
  const [changed, setChanged] = useState(false);

  function mark() { setChanged(true); }

  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <AppHeader goalName="독립자금" onNavigate={onNavigate}/>

      <div className="mx-auto max-w-[1080px] px-6 py-10">
        <div className="mb-8">
          <h1 className="text-[26px] font-bold text-[#111827] tracking-tight">나의 계획 정보</h1>
          <p className="text-[14px] text-[#6B7280] mt-1.5">변경된 항목이 있으면 계획을 다시 계산합니다.</p>
        </div>

        <div className="flex flex-col gap-5">
          <div className="grid items-start gap-6 md:grid-cols-[1.08fr_0.92fr]">
            {/* Goal + financial info */}
            <SectionCard title="목표와 재무 정보">
              <div className="flex flex-col gap-5">
                <p className="text-[12px] font-bold text-[#7C8594]">금융 목표</p>
              <Input label="목표 이름" defaultValue="독립자금" onChange={mark}/>
              <div className="grid grid-cols-2 gap-4">
                  <CurrencyInput label="목표 금액" value="30000000" onChange={mark} className="num h-12"/>
                  <CurrencyInput label="현재 모은 금액" value="8000000" onChange={mark} className="num h-12"/>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-[13px] font-semibold text-[#374151]">목표 날짜</label>
                <input
                  type="month"
                  defaultValue="2028-01"
                  onChange={mark}
                    className="h-12 w-full rounded-[12px] border border-[#E8EAEF] bg-white px-3.5 text-[14px] text-[#111827] transition-all hover:border-[#C7CAFF] focus:border-[#4F58FF] focus:outline-none focus:ring-2 focus:ring-[#4F58FF]/30"
                />
              </div>

                <div className="border-t border-[#E8EAEF] pt-5">
                  <p className="mb-4 text-[12px] font-bold text-[#7C8594]">매달 들어오고 나가는 돈</p>
                  <div className="grid grid-cols-2 gap-4">
                    <CurrencyInput label="월 소득" value="3500000" onChange={mark} hint="세후 기준" className="num h-12"/>
                    <CurrencyInput label="월 고정비" value="1200000" onChange={mark} className="num h-12"/>
                  </div>
                </div>
              </div>
            </SectionCard>

            {/* Basic info + planned expenses */}
            <SectionCard title="기본 정보와 예정지출">
              <div className="flex flex-col gap-5">
                <div className="grid grid-cols-2 gap-4">
                  <Input label="생년월일" type="date" defaultValue="1998-05-12" onChange={mark} className="h-12"/>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[13px] font-semibold text-[#374151]">지역</label>
                    <select
                      onChange={mark}
                      className="h-12 w-full cursor-pointer rounded-[12px] border border-[#E8EAEF] bg-white px-3.5 text-[14px] text-[#111827] transition-all hover:border-[#C7CAFF] focus:border-[#4F58FF] focus:outline-none focus:ring-2 focus:ring-[#4F58FF]/30"
                    >
                      <option>서울특별시</option><option>경기도</option><option>부산광역시</option>
                    </select>
                  </div>
                </div>

                <div className="border-t border-[#E8EAEF] pt-5">
                  <p className="mb-4 text-[12px] font-bold text-[#7C8594]">예정 지출</p>
                  <div className="flex flex-col gap-3">
                    <ScheduledExpenseItem name="일본 여행" date="2026.11.15" amount={1200000} onEdit={mark} onDelete={mark}/>
                    <ScheduledExpenseItem name="노트북 구매" date="2027.02.10" amount={1500000} onEdit={mark} onDelete={mark}/>
                    <button
                      onClick={mark}
                      className="flex cursor-pointer items-center gap-2 rounded-[12px] border-2 border-dashed border-[#C7CAFF] px-4 py-3 text-[13px] font-semibold text-[#4F58FF] transition-all hover:bg-[#EEF0FF]"
                    >
                      <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                        <path d="M7 2.5v9M2.5 7h9" stroke="#4F58FF" strokeWidth="2" strokeLinecap="round"/>
                      </svg>
                      예정 지출 추가
                    </button>
                  </div>
                </div>
              </div>
            </SectionCard>
          </div>

          {/* Change notice */}
          {changed && (
            <NoticeCard
              type="warning"
              title="이 항목을 변경하면 현재 계획을 다시 계산합니다."
              description="새 계획을 선택하기 전까지 기존 계획은 유지됩니다."
              actions={
                <>
                  <Button variant="ghost" size="sm" onClick={() => { setChanged(false); onNavigate(8); }}>취소</Button>
                  <Button size="sm" onClick={() => onNavigate(12)}>변경사항 저장 및 재계산</Button>
                </>
              }
            />
          )}

          {!changed && (
            <div className="flex justify-end">
              <button
                onClick={() => onNavigate(8)}
                className="cursor-pointer rounded-[10px] px-2 py-2 text-[14px] font-semibold text-[#7C8594] transition-colors hover:bg-white hover:text-[#4F58FF]"
              >
                ← 대시보드로
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
