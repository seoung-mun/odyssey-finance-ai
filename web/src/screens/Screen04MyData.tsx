// Screen 4 — MyData Connection
import { useState } from "react";
import { Button, ProgressStepper, OnboardingLayout, IconCheck } from "../components/shared";

const DATA_POINTS = [
  { icon: "📊", label: "월별 유동지출",     desc: "월마다 실제 사용한 생활비 추적" },
  { icon: "📈", label: "소비 변동 패턴",    desc: "지출이 많은 달과 적은 달의 패턴 분석" },
  { icon: "🗂",  label: "카테고리별 소비",  desc: "식비, 교통, 쇼핑 등 분류된 지출 내역" },
  { icon: "🔍", label: "예상 밖 지출 탐지", desc: "갑작스러운 큰 지출 자동 감지" },
];

export default function Screen04MyData({ onNext, onPrev }: { onNext: () => void; onPrev: () => void }) {
  const [connected,  setConnected]  = useState(false);
  const [connecting, setConnecting] = useState(false);

  function handleConnect() {
    setConnecting(true);
    setTimeout(() => { setConnecting(false); setConnected(true); }, 2000);
  }

  return (
    <OnboardingLayout>
      <div className="mb-10 max-w-[480px] mx-auto">
        <ProgressStepper steps={["목표 설정", "예정지출", "마이데이터", "계획 생성"]} current={2}/>
      </div>

      <div className="mb-8">
        <h1 className="text-[30px] font-bold text-[#111827] tracking-tight">실제 소비내역을 연결해주세요</h1>
        <p className="text-[15px] text-[#6B7280] mt-1.5">최근 소비 패턴과 변동성을 분석해 나에게 맞는 계획을 계산합니다.</p>
      </div>

      <div className="flex flex-col gap-5">
        {/* Main connection card */}
        {!connected ? (
          <div className="flex flex-col items-center gap-6 rounded-[20px] border border-[#E8EAEF] bg-white p-8 text-center md:flex-row md:text-left">
            <div className="flex h-[68px] w-[68px] flex-shrink-0 items-center justify-center rounded-[20px] bg-[#F5F6F9]">
              <svg width="38" height="38" viewBox="0 0 38 38" fill="none">
                <rect x="3" y="9" width="32" height="22" rx="4" stroke="#4F58FF" strokeWidth="2"/>
                <path d="M3 16h32" stroke="#4F58FF" strokeWidth="2"/>
                <path d="M8 23h8M8 27h5" stroke="#4F58FF" strokeWidth="1.8" strokeLinecap="round"/>
                <circle cx="27" cy="25" r="6" fill="#EEF0FF" stroke="#4F58FF" strokeWidth="1.8"/>
                <path d="M24.5 25l2 2 3.5-3.5" stroke="#4F58FF" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </div>

            <div className="flex-1">
              <h2 className="text-[20px] font-bold text-[#111827] mb-2">마이데이터 연결</h2>
              <p className="max-w-[400px] text-[14px] leading-relaxed text-[#6B7280]">
                금융결제원 마이데이터 서비스를 통해 최근 24개월의 소비내역을 분석합니다.
                개인 금융 정보는 암호화되어 전송되며 외부에 공유되지 않습니다.
              </p>
              <div className="mt-3 flex items-center justify-center gap-1.5 text-[12px] text-[#8A93A3] md:justify-start">
                <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
                  <path d="M6.5 1.5L10.5 3.5v3c0 2.5-2 4.5-4 5.5-2-1-4-3-4-5.5v-3L6.5 1.5z" stroke="#8A93A3" strokeWidth="1.2"/>
                </svg>
                금융결제원 공식 마이데이터 서비스 이용
              </div>
            </div>

            <Button
              size="lg"
              onClick={handleConnect}
              loading={connecting}
              className="h-14 w-full flex-shrink-0 rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)] md:w-[220px]"
            >
              {!connecting && (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <path d="M8 1v9M5 7l3 3 3-3" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
                  <path d="M2 11v2a1 1 0 001 1h10a1 1 0 001-1v-2" stroke="white" strokeWidth="1.8" strokeLinecap="round"/>
                </svg>
              )}
              마이데이터 연결하기
            </Button>
          </div>
        ) : (
          <div className="bg-white rounded-[20px] border-2 border-[#A3E8CC] p-10 flex flex-col items-center text-center gap-5">
            <div className="w-[64px] h-[64px] rounded-full bg-[#EDFAF4] flex items-center justify-center">
              <IconCheck size={26} color="#0DB97A"/>
            </div>
            <div>
              <h2 className="text-[20px] font-bold text-[#111827] mb-1.5">소비내역 연결 완료</h2>
              <p className="text-[14px] text-[#6B7280]">최근 24개월 데이터 확인 완료</p>
            </div>

            <div className="grid grid-cols-3 gap-4 w-full max-w-[400px]">
              {[
                { label: "분석된 거래", value: "1,247건" },
                { label: "평균 월 소비", value: "843,000원" },
                { label: "데이터 기간",  value: "24개월" },
              ].map((item) => (
                <div key={item.label} className="bg-[#F5F6F9] rounded-[12px] p-3 text-center">
                  <p className="text-[11px] text-[#9CA3AF] mb-1">{item.label}</p>
                  <p className="num text-[14px] font-bold text-[#111827]">{item.value}</p>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Data usage */}
        <section className="border-y border-[#E8EAEF] py-6">
          <h3 className="mb-4 text-[15px] font-bold text-[#111827]">연결하면 확인하는 내용</h3>
          <div className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2">
            {DATA_POINTS.map((d) => (
              <div key={d.label} className="flex items-start gap-3">
                <span className="text-[18px] flex-shrink-0">{d.icon}</span>
                <div>
                  <p className="text-[13px] font-semibold text-[#111827] flex items-center gap-1.5">
                    {d.label}
                    {connected && <IconCheck size={12} color="#0DB97A"/>}
                  </p>
                  <p className="mt-0.5 text-[12px] text-[#7C8594]">{d.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* Privacy note */}
        <div className="flex items-start gap-2.5 px-1">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0 mt-0.5">
            <path d="M7 1.5L12 4v4c0 2.8-2.3 5-5 6.5C4.3 13 2 10.8 2 8V4L7 1.5z" stroke="#C0C7D0" strokeWidth="1.2"/>
          </svg>
          <p className="text-[12px] text-[#9CA3AF] leading-relaxed">
            금융 데이터는 Odyssey 계획 계산에만 사용됩니다. 언제든지 연결을 해제하고 데이터를 삭제할 수 있습니다.
          </p>
        </div>

        <div className="flex items-center justify-between pt-2">
          <button
            onClick={onPrev}
            className="cursor-pointer rounded-[10px] px-2 py-2 text-[14px] font-semibold text-[#7C8594] transition-colors hover:bg-white hover:text-[#4F58FF]"
          >
            ← 이전
          </button>
          {connected && (
            <Button size="lg" onClick={onNext} className="h-14 min-w-[160px] rounded-[14px] py-0 shadow-[0_8px_20px_rgba(79,88,255,0.18)]">
              계획 만들기
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M3.5 8h9M9.5 4.5l3.5 3.5-3.5 3.5" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </Button>
          )}
        </div>
      </div>
    </OnboardingLayout>
  );
}
