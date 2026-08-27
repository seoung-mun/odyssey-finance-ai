// Screen 6 — Spending Detail + Floor Setting
import { useState } from "react";
import { Button, SectionCard, CategorySpendingRow, NoticeCard, Divider } from "../components/shared";

const CATEGORIES = [
  { name: "식비",   amount: 320000, emoji: "🍱", color: "#4F58FF" },
  { name: "교통",   amount:  90000, emoji: "🚇", color: "#7B82FF" },
  { name: "쇼핑",   amount: 210000, emoji: "🛍",  color: "#A5ABFF" },
  { name: "여가",   amount: 160000, emoji: "🎬", color: "#C7CAFF" },
  { name: "기타",   amount: 120000, emoji: "💡", color: "#E2E4FF" },
];
const TOTAL   = CATEGORIES.reduce((s, c) => s + c.amount, 0);
const MAX_AMT = Math.max(...CATEGORIES.map((c) => c.amount));
const MONTHLY_DATA = [
  { m: "3월", v: 720000 }, { m: "4월", v: 960000 }, { m: "5월", v: 830000 },
  { m: "6월", v: 900000 }, { m: "7월", v: 870000 }, { m: "8월", v: 900000 },
];
const MAX_MONTHLY = Math.max(...MONTHLY_DATA.map((d) => d.v));
const SELECTED_PLAN_AMOUNT = 750000;

export default function Screen06SpendingDetail({ onNext, onPrev }: { onNext: () => void; onPrev: () => void }) {
  const [floor, setFloor] = useState("800000");
  const floorN      = Number(floor.replace(/,/g, "")) || 0;
  const LIMIT       = 840000;
  const floorError  = floorN > LIMIT;

  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <div className="max-w-[760px] mx-auto px-6 py-12">
        <div className="mb-8">
          <h1 className="text-[30px] font-bold text-[#111827] tracking-tight mb-2">
            이 계획을 실제로 지킬 수 있을까요?
          </h1>
          <p className="text-[15px] text-[#6B7280]">최근 소비와 선택한 계획의 차이를 먼저 확인해보세요.</p>
        </div>

        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-4 border-y border-[#E8EAEF] py-5 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <p className="text-[12px] font-semibold text-[#6B7280]">최근 월평균 소비</p>
              <p className="num mt-1 text-[28px] font-bold tracking-tight text-[#111827]">{Math.round(TOTAL / 10000)}만원</p>
            </div>
            <div className="sm:text-right">
              <p className="text-[12px] font-semibold text-[#6B7280]">선택한 균형형 계획</p>
              <p className="num mt-1 text-[18px] font-bold text-[#4F58FF]">월 {Math.round(SELECTED_PLAN_AMOUNT / 10000)}만원</p>
              <p className="mt-1 text-[12px] text-[#7C8594]">최근보다 월 {Math.round((TOTAL - SELECTED_PLAN_AMOUNT) / 10000)}만원 줄여야 해요.</p>
            </div>
          </div>

          {/* ── Spending breakdown card ── */}
          <SectionCard title="카테고리별 월평균 소비">
            <div className="flex flex-col gap-4 mb-6">
              {CATEGORIES.map((cat) => (
                <CategorySpendingRow
                  key={cat.name}
                  name={cat.name}
                  amount={cat.amount}
                  max={MAX_AMT * 1.25}
                  color={cat.color}
                  emoji={cat.emoji}
                />
              ))}
            </div>

            <Divider/>

            <div className="flex items-center justify-between mt-4 mb-6">
              <span className="text-[14px] font-semibold text-[#111827]">최근 3개월 월평균</span>
              <span className="num text-[18px] font-bold text-[#111827]">{TOTAL.toLocaleString("ko-KR")}원</span>
            </div>

            {/* Monthly trend mini chart */}
            <div>
              <p className="text-[12px] text-[#7C8594] font-medium mb-3">최근 6개월 추이</p>
              <div className="flex items-end gap-2" style={{ height: 72 }}>
                {MONTHLY_DATA.map(({ m, v }, i) => {
                  const h = Math.round((v / MAX_MONTHLY) * 64);
                  const isLast = i === MONTHLY_DATA.length - 1;
                  return (
                    <div key={m} className="flex-1 flex flex-col items-center gap-1.5">
                      <div className="relative w-full group">
                        <div
                          className={`w-full rounded-t-[4px] transition-all ${isLast ? "bg-[#4F58FF]" : "bg-[#E8EAEF]"}`}
                          style={{ height: h }}
                        />
                        <div className="absolute -top-6 left-1/2 -translate-x-1/2 opacity-0 group-hover:opacity-100 bg-[#111827] text-white text-[10px] font-medium px-2 py-1 rounded-md whitespace-nowrap pointer-events-none transition-opacity">
                          {v.toLocaleString("ko-KR")}원
                        </div>
                      </div>
                      <span className="text-[10px] text-[#9CA3AF]">{m}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          </SectionCard>

          {/* ── Floor setting card ── */}
          <SectionCard>
            <div className="flex flex-col gap-4">
              <div>
                <h3 className="text-[15px] font-bold text-[#111827] mb-1">매달 꼭 필요한 생활비는 얼마인가요?</h3>
                <p className="text-[13px] text-[#6B7280]">입력한 금액보다 낮은 소비 계획은 제안하지 않습니다.</p>
              </div>

              <div>
                <p className="text-[12px] font-semibold text-[#374151] mb-1.5">최소 생활비</p>
                <div className="relative flex items-center">
                  <input
                    type="text"
                    value={Number(floor.replace(/,/g, "")).toLocaleString("ko-KR")}
                    onChange={(e) => setFloor(e.target.value.replace(/[^0-9]/g, ""))}
                    className={`w-full rounded-[12px] border px-4 py-3.5 text-[22px] num font-bold text-[#111827] pr-12 focus:outline-none focus:ring-2 transition-all
                      ${floorError ? "border-[#FEB2B2] focus:border-[#E53E3E] focus:ring-[#E53E3E]/20" : "border-[#E8EAEF] hover:border-[#C7CAFF] focus:border-[#4F58FF] focus:ring-[#4F58FF]/20"}`}
                  />
                  <span className="absolute right-4 text-[14px] text-[#9CA3AF] font-medium">원</span>
                </div>

                {/* Range indicator */}
                {!floorError && (
                  <div className="mt-2 flex items-center gap-2">
                    <div className="flex-1 bg-[#E8EAEF] rounded-full h-1.5 overflow-hidden">
                      <div
                        className="h-full rounded-full bg-[#4F58FF] transition-all"
                        style={{ width: `${Math.min(100, (floorN / LIMIT) * 100)}%` }}
                      />
                    </div>
                    <span className="num text-[11px] text-[#9CA3AF]">{LIMIT.toLocaleString("ko-KR")}원 한도</span>
                  </div>
                )}
              </div>

              {floorError && (
                <NoticeCard
                  type="error"
                  title={`현재 목표를 유지하려면 월 ${LIMIT.toLocaleString("ko-KR")}원 이하로 설정해야 합니다.`}
                  description="입력한 소비 하한이 목표 달성에 필요한 최대 한도를 초과합니다."
                />
              )}

              {!floorError && floorN > 0 && (
                <div className="flex items-center justify-between border-t border-[#E8EAEF] pt-4">
                  <span className="text-[13px] text-[#6B7280]">선택한 균형형 계획보다</span>
                  <span className={`num text-[14px] font-semibold ${floorN > SELECTED_PLAN_AMOUNT ? "text-[#B45309]" : "text-[#4F58FF]"}`}>
                    {Math.abs(floorN - SELECTED_PLAN_AMOUNT).toLocaleString("ko-KR")}원 {floorN > SELECTED_PLAN_AMOUNT ? "높아요" : "여유 있어요"}
                  </span>
                </div>
              )}

              <div className="flex justify-end">
                <Button disabled={floorError || floorN === 0} onClick={onNext} className="h-14 rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]">
                  이 기준으로 계획 다시 계산
                </Button>
              </div>
            </div>
          </SectionCard>

          <div className="flex items-center justify-between pt-2">
            <button
              onClick={onPrev}
              className="cursor-pointer rounded-[10px] px-2 py-2 text-[14px] font-semibold text-[#7C8594] transition-colors hover:bg-white hover:text-[#4F58FF]"
            >
              ← 계획 선택으로 돌아가기
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
