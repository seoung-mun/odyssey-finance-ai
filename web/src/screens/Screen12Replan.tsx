// Screen 12 — Replan Comparison
import { useState } from "react";
import { AppHeader, Button, PlanCard, IconWarning } from "../components/shared";

const NEW_PLANS = [
  { label: "PLAN A", title: "부담 완화형", description: "현재보다 월 5만원 적게 · 안정성 7%p 높음", amount: 700000, rate: 70, warning: false, warningText: undefined, percentUnder: undefined },
  { label: "PLAN B", title: "균형 회복형", description: "현재보다 월 14만원 적게 · 안정성 17%p 높음", amount: 610000, rate: 80, warning: false, warningText: undefined, percentUnder: undefined },
  { label: "PLAN C", title: "목표 우선형", description: "현재보다 월 23만원 적게 · 안정성 27%p 높음", amount: 520000, rate: 90, warning: true,
    warningText: "현재 평균 소비의 58% 수준입니다. 지속하기 매우 어려울 수 있어요.",
    percentUnder: 6 },
];

export default function Screen12Replan({ onNavigate, fromMonthly = false }: {
  onNavigate: (s: number) => void; fromMonthly?: boolean;
}) {
  const [selected, setSelected] = useState<number | null>(null);

  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <AppHeader goalName="독립자금" onNavigate={onNavigate}/>

      <div className="max-w-[1080px] mx-auto px-6 py-10">
        <div className="mb-8">
          <h1 className="text-[26px] font-bold text-[#111827] tracking-tight mb-2">
            {fromMonthly ? "9월 계획이 새로 계산되었습니다." : "새 계획을 선택하거나 현재 계획을 유지하세요"}
          </h1>
          <p className="text-[14px] text-[#6B7280]">
            {fromMonthly
              ? "지난달 소비와 현재 목표 상황을 반영해 이번 달 계획을 다시 계산했습니다."
              : "최근 소비를 반영한 새 계획과 현재 계획의 차이를 비교해보세요."}
          </p>
        </div>

        <div className="flex flex-col gap-6">
          {/* ── Current active plan ── */}
          <div className="bg-white rounded-[20px] border-2 border-[#E8EAEF] p-6">
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-2.5">
                <span className="text-[12px] font-bold text-[#9CA3AF] uppercase tracking-wide">현재 계획</span>
                <span className="bg-[#F3F4F6] text-[#6B7280] text-[11px] font-bold px-2.5 py-1 rounded-full">유지 중</span>
              </div>
              <Button variant="outline" size="sm" onClick={() => onNavigate(8)}>현재 계획 유지하기</Button>
            </div>

            <div className="grid grid-cols-4 gap-6">
              <div>
                <p className="text-[12px] text-[#9CA3AF] mb-1.5">월 유동지출 한도</p>
                <p className="num text-[24px] font-bold text-[#111827]">750,000<span className="text-[13px] text-[#9CA3AF] ml-1">원</span></p>
              </div>
              <div>
                <p className="text-[12px] text-[#9CA3AF] mb-1.5">계획 안정성</p>
                <p className="num text-[24px] font-bold text-[#E09B00]">63%</p>
                <p className="text-[11px] text-[#9CA3AF] mt-0.5">최근 소비 반영 재산정</p>
              </div>
              <div>
                <p className="text-[12px] text-[#9CA3AF] mb-1.5">이번 달 이미 사용</p>
                <p className="num text-[24px] font-bold text-[#111827]">430,000<span className="text-[13px] text-[#9CA3AF] ml-1">원</span></p>
              </div>
              <div>
                <p className="text-[12px] text-[#9CA3AF] mb-1.5">목표까지</p>
                <p className="num text-[24px] font-bold text-[#111827]">28<span className="text-[13px] text-[#9CA3AF] ml-1">개월</span></p>
              </div>
            </div>

            <div className="mt-4 flex items-start gap-2.5 border-t border-[#FDE68A] pt-4">
              <div className="mt-0.5 flex-shrink-0">
                <IconWarning size={14}/>
              </div>
              <p className="text-[12px] text-[#92400E]">
                최근 소비를 반영하면 계획 안정성이 <span className="font-bold">80%→63%</span>로 낮아집니다. 아래 제안과 비교해보세요.
              </p>
            </div>
          </div>

          {/* Separator */}
          <div className="flex items-center gap-4">
            <div className="flex-1 border-t border-[#E8EAEF]"/>
            <span className="text-[11px] font-bold text-[#9CA3AF] uppercase tracking-widest">새로운 계획 제안</span>
            <div className="flex-1 border-t border-[#E8EAEF]"/>
          </div>

          {/* New plan cards */}
          <div className="grid grid-cols-1 gap-5 md:grid-cols-3">
            {NEW_PLANS.map((plan, i) => (
              <PlanCard
                key={plan.label}
                {...plan}
                selected={selected === i}
                onSelect={() => setSelected(i)}
                compact
                hideAction
              />
            ))}
          </div>

          {/* Apply CTA */}
          {selected !== null && (
            <div className="flex flex-col gap-4 rounded-[16px] border border-[#E8EAEF] bg-white px-6 py-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p className="text-[14px] font-bold text-[#111827]">
                  {NEW_PLANS[selected].title} 선택됨
                </p>
                <p className="text-[12px] text-[#9CA3AF] mt-0.5">
                  월 {Math.round(NEW_PLANS[selected].amount / 10000)}만원 · 계획 안정성 {NEW_PLANS[selected].rate}%
                </p>
              </div>
              <div className="flex flex-col gap-3 sm:items-end">
                <p className="text-[12px] text-[#9CA3AF]">
                  이번 달 남은 권장 소비:{" "}
                  <span className="num font-bold text-[#4F58FF]">{(NEW_PLANS[selected].amount - 430000).toLocaleString("ko-KR")}원</span>
                </p>
                <Button onClick={() => onNavigate(8)} className="h-14 rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]">이 계획으로 변경하기</Button>
              </div>
            </div>
          )}

          <div className="flex justify-start">
            <button
              onClick={() => onNavigate(8)}
              className="cursor-pointer rounded-[10px] px-2 py-2 text-[14px] font-semibold text-[#7C8594] transition-colors hover:bg-white hover:text-[#4F58FF]"
            >
              ← 대시보드로
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
