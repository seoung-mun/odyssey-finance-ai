import { useState } from "react";
import Screen01Login           from "./screens/Screen01Login";
import Screen02Onboarding      from "./screens/Screen02Onboarding";
import Screen03PlannedExpenses from "./screens/Screen03PlannedExpenses";
import Screen04MyData          from "./screens/Screen04MyData";
import Screen05PlanCompare     from "./screens/Screen05PlanCompare";
import Screen06SpendingDetail  from "./screens/Screen06SpendingDetail";
import Screen07PlanConfirm     from "./screens/Screen07PlanConfirm";
import Screen08Dashboard       from "./screens/Screen08Dashboard";
import Screen09EditPlan        from "./screens/Screen09EditPlan";
import Screen10RecalcModal     from "./screens/Screen10RecalcModal";
import Screen12Replan          from "./screens/Screen12Replan";

// ─── Screen 11 — Dashboard + spending alert ─────────────────────
function Screen11({ nav }: { nav: (s: number) => void }) {
  return <Screen08Dashboard onNavigate={nav} showAlert={true}/>;
}

// ─── Screen 13 — Dashboard + monthly notification ───────────────
function Screen13({ nav }: { nav: (s: number) => void }) {
  return <Screen08Dashboard onNavigate={nav} monthlyAlert/>;
}

// ─── Minimal demo navigator (prototype-only, not part of product UI) ──
const SCREEN_LABELS: Record<number, string> = {
  1: "로그인", 2: "기본정보", 3: "예정지출", 4: "마이데이터",
  5: "계획 비교", 6: "소비 상세", 7: "계획 확인", 8: "대시보드",
  9: "계획 수정", 10: "재계산", 11: "소비 경고", 12: "Replan", 13: "월 알림",
};

function DemoNav({ screen, onGo }: { screen: number; onGo: (n: number) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="fixed bottom-5 right-5 z-[100]">
      {open && (
        <div className="absolute bottom-full right-0 mb-2 bg-white border border-[#E8EAEF] rounded-[16px] shadow-xl shadow-black/8 py-2 w-[160px] overflow-hidden">
          {Object.entries(SCREEN_LABELS).map(([n, label]) => (
            <button
              key={n}
              onClick={() => { onGo(Number(n)); setOpen(false); }}
              className={`w-full text-left px-4 py-2 text-[12px] font-medium transition-colors cursor-pointer
                ${screen === Number(n)
                  ? "bg-[#EEF0FF] text-[#4F58FF]"
                  : "text-[#374151] hover:bg-[#F5F6F9]"}`}
            >
              <span className="text-[#C0C7D0] mr-2">{n}.</span>{label}
            </button>
          ))}
        </div>
      )}
      <div className="flex items-center gap-1 bg-white border border-[#E8EAEF] rounded-full pl-1.5 pr-1 py-1 shadow-md shadow-black/6">
        <button
          onClick={() => onGo(Math.max(1, screen - 1))}
          disabled={screen === 1}
          className="w-6 h-6 rounded-full flex items-center justify-center text-[#9CA3AF] hover:bg-[#F5F6F9] hover:text-[#374151] disabled:opacity-25 transition-colors cursor-pointer"
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
            <path d="M6.5 1.5L3 5l3.5 3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        </button>
        <button
          onClick={() => setOpen(o => !o)}
          className="text-[11px] font-semibold text-[#374151] px-2 min-w-[90px] text-center hover:text-[#4F58FF] transition-colors cursor-pointer"
        >
          {SCREEN_LABELS[screen]}
        </button>
        <button
          onClick={() => onGo(Math.min(13, screen + 1))}
          disabled={screen === 13}
          className="w-6 h-6 rounded-full flex items-center justify-center text-[#9CA3AF] hover:bg-[#F5F6F9] hover:text-[#374151] disabled:opacity-25 transition-colors cursor-pointer"
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
            <path d="M3.5 1.5L7 5l-3.5 3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        </button>
      </div>
    </div>
  );
}

// ─── App root ───────────────────────────────────────────────────
export default function App() {
  const [screen, setScreen] = useState(1);
  const [modal,  setModal]  = useState(false);

  function nav(s: number) {
    if (s === 10) { setModal(true); return; }
    setScreen(s);
  }

  function go(n: number) {
    setModal(false);
    setScreen(n);
  }

  return (
    <div className="h-full">
      <div className="min-h-full">
        {screen ===  1 && <Screen01Login           onNext={() => setScreen(2)}/>}
        {screen ===  2 && <Screen02Onboarding      onNext={() => setScreen(3)} onPrev={() => setScreen(1)}/>}
        {screen ===  3 && <Screen03PlannedExpenses onNext={() => setScreen(4)} onPrev={() => setScreen(2)}/>}
        {screen ===  4 && <Screen04MyData          onNext={() => setScreen(5)} onPrev={() => setScreen(3)}/>}
        {screen ===  5 && <Screen05PlanCompare     onNext={() => setScreen(7)} onDetailView={() => setScreen(6)}/>}
        {screen ===  6 && <Screen06SpendingDetail  onNext={() => setScreen(5)} onPrev={() => setScreen(5)}/>}
        {screen ===  7 && <Screen07PlanConfirm     onNext={() => setScreen(8)} onPrev={() => setScreen(5)}/>}
        {screen ===  8 && <Screen08Dashboard       onNavigate={nav}/>}
        {screen ===  9 && <Screen09EditPlan        onNavigate={nav}/>}
        {screen === 10 && <Screen08Dashboard       onNavigate={nav}/>}
        {screen === 11 && <Screen11               nav={nav}/>}
        {screen === 12 && <Screen12Replan          onNavigate={nav}/>}
        {screen === 13 && <Screen13               nav={nav}/>}
      </div>

      <Screen10RecalcModal
        open={modal || screen === 10}
        onClose={() => { setModal(false); if (screen === 10) setScreen(8); }}
        onConfirm={() => { setModal(false); setScreen(12); }}
      />

      <DemoNav screen={screen} onGo={go}/>
    </div>
  );
}
