// Screen 5 — Plan Comparison (most important screen)
import { useState } from "react";
import { Button, ProgressStepper, PlanCard, OnboardingLayout } from "../components/shared";

const PLANS = [
  { label: "PLAN A", title: "소비 여유형", description: "매달 쓸 수 있는 금액을 가장 넉넉하게 잡았어요.", amount: 900000, rate: 70, warning: false, warningText: undefined, percentUnder: undefined },
  { label: "PLAN B", title: "균형형", description: "소비 여유와 계획 안정성을 함께 고려했어요.", amount: 750000, rate: 80, warning: false, warningText: undefined, percentUnder: undefined },
  { label: "PLAN C", title: "목표 우선형", description: "월 사용 금액을 낮춰 계획 안정성을 높였어요.", amount: 620000, rate: 90, warning: true,
    warningText: "최근 소비 패턴과 크게 차이 나는 계획입니다. 지속하기 어려울 수 있어요.",
    percentUnder: 8 },
];

export default function Screen05PlanCompare({ onNext, onDetailView }: {
  onNext: (idx: number) => void; onDetailView: () => void;
}) {
  const [selected, setSelected] = useState(1);

  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <div className="max-w-[1040px] mx-auto px-6 py-12">
        {/* Stepper */}
        <div className="mb-10 max-w-[480px] mx-auto">
          <ProgressStepper steps={["목표 설정", "예정지출", "마이데이터", "계획 생성"]} current={3}/>
        </div>

        {/* Title */}
        <div className="text-center mb-12">
          <h1 className="text-[32px] font-bold text-[#111827] tracking-tight mb-2">
            매달 쓸 수 있는 금액을 선택해보세요
          </h1>
          <p className="text-[15px] text-[#6B7280]">금액이 낮을수록 목표 달성 계획은 더 안정적이에요.</p>
        </div>

        {/* Plan cards */}
        <div className="mb-8 grid grid-cols-1 gap-5 md:grid-cols-3">
          {PLANS.map((plan, i) => (
            <PlanCard
              key={plan.label}
              {...plan}
              selected={selected === i}
              onSelect={() => setSelected(i)}
              onDetailView={plan.percentUnder !== undefined ? onDetailView : undefined}
              compact
              hideAction
            />
          ))}
        </div>

        {/* Selection summary bar */}
        <div className="mb-6 flex flex-col gap-4 rounded-[16px] border border-[#E8EAEF] bg-white px-6 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-[#4F58FF]"/>
              <span className="text-[13px] text-[#6B7280]">선택한 계획</span>
            </div>
            <div className="h-4 w-px bg-[#E8EAEF]"/>
            <span className="text-[14px] font-bold text-[#111827]">{PLANS[selected].title}</span>
            <span className="text-[13px] text-[#6B7280]">·</span>
            <span className="num text-[14px] font-bold text-[#4F58FF]">월 {Math.round(PLANS[selected].amount / 10000)}만원</span>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-[13px] text-[#6B7280]">계획 안정성</span>
            <span className="num text-[16px] font-bold text-[#0DB97A]">{PLANS[selected].rate}%</span>
            <Button size="lg" onClick={() => onNext(selected)} className="h-14 rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]" iconRight={
              <svg width="15" height="15" viewBox="0 0 15 15" fill="none">
                <path d="M3 7.5h9M9 4l3.5 3.5L9 11" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            }>
              선택한 계획으로 시작
            </Button>
          </div>
        </div>

        {/* Context note */}
        <p className="text-center text-[12px] text-[#9CA3AF]">
          계획 안정성은 과거 소비 변동을 반영한 시뮬레이션에서 해당 계획의 소비 기준을 충족하는 정도를 나타냅니다.
        </p>
      </div>
    </div>
  );
}
