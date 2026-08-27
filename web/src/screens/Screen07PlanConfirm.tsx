// Screen 7 — Final Plan Confirmation
import { Button, GoalProgressBar } from "../components/shared";

const PLAN = {
  amount: 750000, rate: 80, goalName: "독립자금",
  goalTotal: 30000000, currentSaved: 8000000, months: 28,
};

export default function Screen07PlanConfirm({ onNext, onPrev }: { onNext: () => void; onPrev: () => void }) {
  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <div className="max-w-[560px] mx-auto px-6 py-14">
        <div className="text-center mb-10">
          <h1 className="text-[30px] font-bold text-[#111827] tracking-tight mb-2">이 계획으로 시작할까요?</h1>
          <p className="text-[15px] text-[#6B7280]">선택한 계획의 핵심 정보를 최종 확인해보세요.</p>
        </div>

        <div className="flex flex-col gap-5">
          {/* ── 선택한 계획 + 목표 요약 ── */}
          <div className="rounded-[20px] border border-[#E8EAEF] bg-white p-6">
            <div className="flex items-end justify-between gap-4">
              <div>
                <p className="text-[12px] font-bold text-[#4F58FF]">선택한 균형형 계획</p>
                <p className="mt-2 text-[14px] font-semibold text-[#6B7280]">매달 쓸 수 있는 금액</p>
              </div>
              <p className="num text-[42px] font-bold leading-none tracking-tight text-[#4F58FF]">
                {Math.round(PLAN.amount / 10000).toLocaleString("ko-KR")}
                <span className="ml-1.5 text-[20px] font-semibold text-[#7B82FF]">만원</span>
              </p>
            </div>

            <div className="my-5 flex items-center justify-between border-y border-[#E8EAEF] py-4">
              <span className="text-[13px] font-medium text-[#6B7280]">계획 안정성</span>
              <span className="num text-[20px] font-bold text-[#0DB97A]">{PLAN.rate}%</span>
            </div>

            <h3 className="mb-3 text-[12px] font-bold text-[#7C8594]">독립자금 목표 현황</h3>
            <GoalProgressBar current={PLAN.currentSaved} total={PLAN.goalTotal} showLabels={false}/>
            <div className="grid grid-cols-3 gap-3 mt-5">
              {[
                { label: "목표 금액",   value: `${(PLAN.goalTotal   / 10000).toLocaleString()}만원` },
                { label: "현재 저축",   value: `${(PLAN.currentSaved/ 10000).toLocaleString()}만원` },
                { label: "남은 기간",   value: `${PLAN.months}개월` },
              ].map(({ label, value }) => (
                <div key={label} className="rounded-[12px] bg-[#F5F6F9] py-4 text-center">
                  <p className="mb-1.5 text-[11px] text-[#7C8594]">{label}</p>
                  <p className="num text-[15px] font-bold text-[#111827]">{value}</p>
                </div>
              ))}
            </div>
          </div>

          {/* ── 계획에 반영된 항목 ── */}
          <div className="border-y border-[#E8EAEF] py-5">
            <h3 className="mb-4 text-[12px] font-bold text-[#7C8594]">계획에 반영한 내용</h3>
            <div className="flex flex-col gap-3">
              {[
                { icon: "💰", label: "월 소득",      value: "3,500,000원" },
                { icon: "📌", label: "월 고정지출",   value: "1,200,000원" },
                { icon: "✈️",  label: "예정지출 2건", value: "일본 여행 · 노트북", sub: "계획 계산에 반영됨" },
              ].map(({ icon, label, value, sub }) => (
                <div key={label} className="flex items-center justify-between py-2 border-b border-[#F5F6F9] last:border-0">
                  <div className="flex items-center gap-2.5">
                    <span className="text-[16px] w-6 text-center">{icon}</span>
                    <div>
                      <p className="text-[13px] font-semibold text-[#374151]">{label}</p>
                      {sub && <p className="mt-0.5 text-[11px] text-[#7C8594]">{sub}</p>}
                    </div>
                  </div>
                  <span className="num text-[13px] font-semibold text-[#111827]">{value}</span>
                </div>
              ))}
            </div>
          </div>

          {/* ── 안내 메시지 ── */}
          <div className="flex items-start gap-3 px-1 py-1">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" className="flex-shrink-0 mt-0.5">
              <circle cx="8" cy="8" r="6.5" stroke="#4F58FF" strokeWidth="1.4"/>
              <path d="M8 7v4M8 5.5v.5" stroke="#4F58FF" strokeWidth="1.5" strokeLinecap="round"/>
            </svg>
            <p className="text-[13px] leading-relaxed text-[#6B7280]">
              계획 확정 후에는 대시보드에서 매달 실제 소비를 자동으로 추적하고, 필요 시 계획을 다시 계산해드립니다.
            </p>
          </div>

          {/* ── Actions ── */}
          <div className="flex gap-3 pt-1">
            <Button variant="outline" className="h-14 flex-1 rounded-[14px] py-0" size="lg" onClick={onPrev}>
              다시 선택
            </Button>
            <Button className="h-14 flex-1 rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]" size="lg" onClick={onNext}>
              이 계획으로 시작
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M3.5 8h9M9.5 4.5l3.5 3.5-3.5 3.5" stroke="white" strokeWidth="1.8"
                  strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
