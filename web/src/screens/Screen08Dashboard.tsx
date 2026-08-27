// Screen 8 — Main Dashboard
import { useState, useEffect } from "react";
import { AppHeader, Button, GoalProgressBar, AlertBanner, IconWarning } from "../components/shared";

// ── Plan data ────────────────────────────────────────────────────
const MONTHS    = 28;
const START_AMT = 8_000_000;
const GOAL_AMT  = 30_000_000;

// Demo: user is 4 months into their plan, slightly ahead of schedule
const CURRENT_T  = 4;
const ACTUAL_AMT = 11_800_000;   // actual savings today

// ── Chart geometry ───────────────────────────────────────────────
const W = 760, H = 360;
// PAD.top=90 gives the island/lighthouse 90px of vertical room inside the viewBox
const PAD = { top: 90, right: 88, bottom: 52, left: 80 };
const CW = W - PAD.left - PAD.right;   // 592
const CH = H - PAD.top  - PAD.bottom;  // 218

interface ChartPt { t: number; lower: number; mid: number; upper: number; }

function formatManwon(amount: number) {
  return Math.round(amount / 10_000).toLocaleString("ko-KR");
}

function buildData(): ChartPt[] {
  return Array.from({ length: MONTHS + 1 }, (_, i) => {
    const f   = i / MONTHS;
    const mid = START_AMT + f * (GOAL_AMT - START_AMT);
    const spread = i * i * 28_000;   // band fans out quadratically (Monte Carlo)
    return { t: i, lower: Math.max(START_AMT - 400_000, mid - spread), mid, upper: mid + spread };
  });
}

function toPx(t: number, v: number): [number, number] {
  return [
    PAD.left + (t / MONTHS) * CW,
    PAD.top  + CH * (1 - (v - START_AMT) / (GOAL_AMT - START_AMT)),
  ];
}

function smoothPath(pts: [number, number][]): string {
  if (pts.length < 2) return "";
  let d = `M ${pts[0][0]} ${pts[0][1]}`;
  for (let i = 1; i < pts.length; i++) {
    const [x0, y0] = pts[i - 1], [x1, y1] = pts[i];
    const cx = (x0 + x1) / 2;
    d += ` C ${cx} ${y0}, ${cx} ${y1}, ${x1} ${y1}`;
  }
  return d;
}

// ── Plan status derived from actual vs planned position ──────────
function derivePlanStatus(data: ChartPt[]) {
  const pt   = data[CURRENT_T];
  const diff = ACTUAL_AMT - pt.mid;
  const halfBand = pt.upper - pt.mid;

  let label: string, shortLabel: string, color: string, recalcPrompt: boolean;
  if (ACTUAL_AMT > pt.mid + halfBand * 0.4) {
    label = "계획보다 앞서가고 있어요";
    shortLabel = "계획보다 앞섬"; color = "#0DB97A"; recalcPrompt = false;
  } else if (ACTUAL_AMT >= pt.mid - (pt.mid - pt.lower) * 0.35) {
    label = "계획을 잘 따라가고 있어요";
    shortLabel = "계획 범위 안"; color = "#0DB97A"; recalcPrompt = false;
  } else if (ACTUAL_AMT >= pt.lower) {
    label = "계획보다 조금 느리지만 예상 범위 안이에요";
    shortLabel = "범위 안, 주의"; color = "#E09B00"; recalcPrompt = true;
  } else {
    label = "현재 소비 흐름이 계획 범위를 벗어났어요";
    shortLabel = "범위 이탈"; color = "#E53E3E"; recalcPrompt = true;
  }

  const sign     = diff >= 0 ? "+" : "";
  const diffStr  = `${sign}${Math.round(diff / 10000)}만원`;
  return { label, shortLabel, color, diffStr, diff, recalcPrompt };
}

// Actual savings history — shows the path already traveled
const ACTUAL_HISTORY: [number, number][] = [
  [0, 8_000_000],
  [1, 8_850_000],
  [2, 9_880_000],
  [3, 10_740_000],
  [4, ACTUAL_AMT],
];

// ── Route chart ──────────────────────────────────────────────────
function RouteChart({ onNavigate }: { onNavigate: (s: number) => void }) {
  const [phase, setPhase] = useState(0);
  const [hoveredT, setHoveredT] = useState<number | null>(null);
  useEffect(() => {
    const t1 = setTimeout(() => setPhase(1), 80);
    const t2 = setTimeout(() => setPhase(2), 900);
    return () => { clearTimeout(t1); clearTimeout(t2); };
  }, []);

  const data   = buildData();
  const status = derivePlanStatus(data);

  // Planned route paths
  const midPts   = data.map(d => toPx(d.t, d.mid)   as [number, number]);
  const upperPts = data.map(d => toPx(d.t, d.upper) as [number, number]);
  const lowerPts = data.map(d => toPx(d.t, d.lower) as [number, number]);
  const midPath   = smoothPath(midPts);
  const upperPath = smoothPath(upperPts);
  const lowerPath = smoothPath(lowerPts);

  // Closed band path (navigable waters)
  const revLower = [...lowerPts].reverse();
  const bandPath = `${upperPath} L ${lowerPts[lowerPts.length-1].join(" ")} `
    + revLower.map((p, i) => i === 0 ? "" : `L ${p.join(" ")}`).join(" ")
    + " Z";

  // Danger zone: below lower bound → x-axis bottom
  const dangerPath = `${lowerPath} L ${PAD.left + CW} ${PAD.top + CH} L ${PAD.left} ${PAD.top + CH} Z`;

  // Actual traveled path (t=0 → CURRENT_T)
  const actualPts  = ACTUAL_HISTORY.map(([t, v]) => toPx(t, v) as [number, number]);
  const actualPath = smoothPath(actualPts);

  // Boat data position
  const [rawBoatX, rawBoatY] = toPx(CURRENT_T, ACTUAL_AMT);

  // Each pad = element visual extent + 20-unit margin from plot boundary.
  // Mast: 18 up → pad 38. Badge above: 34+24=58 → accounted by badge's own clamp.
  // Hull: 8 down → pad 28. Wake: 18 left → pad 38. Sail: 14 right → pad 34.
  const BOAT_PAD_TOP    = 38;
  const BOAT_PAD_BOTTOM = 28;
  const BOAT_PAD_LEFT   = 38;
  const BOAT_PAD_RIGHT  = 34;

  const boatX = Math.max(PAD.left  + BOAT_PAD_LEFT,  Math.min(PAD.left  + CW - BOAT_PAD_RIGHT,  rawBoatX));
  const boatY = Math.max(PAD.top   + BOAT_PAD_TOP,   Math.min(PAD.top   + CH - BOAT_PAD_BOTTOM, rawBoatY));

  // Orient along actual traveled direction
  const [ax0, ay0] = toPx(CURRENT_T - 1, ACTUAL_HISTORY[CURRENT_T - 1][1]);
  const [ax1, ay1] = toPx(CURRENT_T,     ACTUAL_AMT);
  const boatAngleDeg = (Math.atan2(ay1 - ay0, ax1 - ax0) * 180) / Math.PI;

  // Gap between actual and planned at current t (for the connecting line)
  const [, midAtCurrentY] = toPx(CURRENT_T, data[CURRENT_T].mid);

  // Goal (island + lighthouse)
  const [goalX, goalY] = toPx(MONTHS, GOAL_AMT);

  const axisY = PAD.top + CH;
  const ROUTE_LEN = 1800;

  const yTicks = [8, 14, 20, 26, 30].map(m => ({
    v: m * 1_000_000,
    label: (m * 100).toLocaleString("ko-KR"),
  }));
  const xTicks = [0, 7, 14, 21, 28].map(t => ({ t, label: t === MONTHS ? "목표" : `+${t}개월` }));

  const hoveredPoint = hoveredT === null ? null : data[hoveredT];
  const hoveredActual = hoveredT === null
    ? undefined
    : ACTUAL_HISTORY.find(([t]) => t === hoveredT)?.[1];
  const hoverX = hoveredPoint ? toPx(hoveredPoint.t, hoveredPoint.mid)[0] : 0;
  const hoverY = hoveredPoint ? toPx(hoveredPoint.t, hoveredPoint.mid)[1] : 0;
  const tooltipWidth = 176;
  const tooltipHeight = hoveredActual === undefined ? 74 : 92;
  const tooltipX = Math.max(
    PAD.left + 8,
    Math.min(PAD.left + CW - tooltipWidth - 8, hoverX + 12),
  );
  const tooltipY = PAD.top + 10;

  return (
    <div>
      {/* ── Status header ── */}
      <div className="flex items-start justify-between mb-5">
        <div>
          <h2 className="text-[15px] font-bold text-[#111827] mb-1">항로 현황</h2>
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: status.color }}/>
            <span className="text-[13px] font-semibold" style={{ color: status.color }}>
              {status.label}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-5">
          <div className="text-right">
            <p className="text-[10px] font-medium text-[#9CA3AF] uppercase tracking-wide mb-1">기준 경로 대비</p>
            <p className="num text-[18px] font-bold leading-none"
              style={{ color: status.diff >= 0 ? "#0DB97A" : "#E09B00" }}>
              {status.diffStr}
            </p>
          </div>
          {status.recalcPrompt && (
            <Button variant="outline" size="sm" onClick={() => onNavigate(10)}>
              다시 계산
            </Button>
          )}
        </div>
      </div>

      {/* ── Legend ── */}
      <div className="flex items-center gap-5 text-[11px] text-[#9CA3AF] mb-3">
        <span className="flex items-center gap-1.5">
          <svg width="20" height="3"><line x1="0" y1="1.5" x2="20" y2="1.5"
            stroke="#1A6FA8" strokeWidth="1.8" strokeDasharray="6 4"/></svg>
          계획 기준 경로
        </span>
        <span className="flex items-center gap-1.5">
          <svg width="20" height="3"><line x1="0" y1="1.5" x2="20" y2="1.5"
            stroke={status.color} strokeWidth="2.5"/></svg>
          실제 항로
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-5 h-3 rounded-sm"
            style={{ background: "rgba(30,130,190,0.18)", border: "1px dashed rgba(30,130,190,0.4)" }}/>
          정상 변동 범위
        </span>
      </div>

      {/* ── SVG nautical chart ── */}
      <svg
        viewBox={`0 0 ${W} ${H}`}
        style={{ width: "100%", height: "auto", display: "block" }}
        fill="none"
        role="img"
        aria-label="독립자금 계획 경로와 실제 저축액을 보여주는 항로 차트"
        onMouseLeave={() => setHoveredT(null)}
      >
        <defs>
          {/* Ocean gradient for the navigable band */}
          <linearGradient id="seaFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%"   stopColor="#1E82BE" stopOpacity="0.22"/>
            <stop offset="50%"  stopColor="#1874AE" stopOpacity="0.14"/>
            <stop offset="100%" stopColor="#0F5A8A" stopOpacity="0.07"/>
          </linearGradient>
          {/* Chart background — open water */}
          <linearGradient id="chartBg" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%"   stopColor="#EBF5FC" stopOpacity="1"/>
            <stop offset="100%" stopColor="#F4F9FD" stopOpacity="1"/>
          </linearGradient>
          {/* Actual path glow */}
          <filter id="actualGlow" x="-8%" y="-40%" width="116%" height="180%">
            <feGaussianBlur in="SourceGraphic" stdDeviation="2.5" result="blur"/>
            <feMerge>
              <feMergeNode in="blur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
          {/* Boat glow */}
          <filter id="boatGlow" x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="3" result="blur"/>
            <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
          </filter>
          <filter id="tooltipShadow" x="-12%" y="-20%" width="124%" height="140%">
            <feDropShadow dx="0" dy="2" stdDeviation="2" floodColor="#111827" floodOpacity="0.08"/>
          </filter>
          <clipPath id="chartClip">
            <rect x={PAD.left} y={PAD.top} width={CW} height={CH}/>
          </clipPath>
        </defs>

        {/* ── Layer 1: chart background (open water tint) ── */}
        <rect x={PAD.left} y={PAD.top} width={CW} height={CH}
          fill="url(#chartBg)" rx="2"/>

        {/* ── Layer 2: danger zone — below lower bound (shoal water) ── */}
        <path d={dangerPath}
          fill="rgba(220,80,30,0.04)"
          clipPath="url(#chartClip)"/>

        {/* ── Layer 3: navigable band — the safe sea channel ── */}
        <path d={bandPath}
          fill="url(#seaFill)"
          clipPath="url(#chartClip)"
          style={{ opacity: 0, animation: "bandFadeIn 1.4s ease 0.2s forwards" }}/>
        {/* Band edge — upper (port side) */}
        <path d={upperPath}
          stroke="#2D8EC4" strokeWidth="1" strokeOpacity="0.3"
          strokeDasharray="5 6" fill="none" clipPath="url(#chartClip)"/>
        {/* Band edge — lower (starboard side) */}
        <path d={lowerPath}
          stroke="#2D8EC4" strokeWidth="0.8" strokeOpacity="0.22"
          strokeDasharray="5 6" fill="none" clipPath="url(#chartClip)"/>

        {/* ── Layer 4: Y-axis grid ── */}
        <text x={PAD.left - 8} y={PAD.top - 16} fontSize="10"
          fill="#7C8594" fontFamily="Noto Sans KR, sans-serif" textAnchor="end">
          금액 (만원)
        </text>
        {yTicks.map(({ v, label }) => {
          const [, gy] = toPx(0, v);
          const isGoal = v === GOAL_AMT;
          return (
            <g key={v}>
              <line x1={PAD.left} y1={gy} x2={PAD.left + CW} y2={gy}
                stroke={isGoal ? "rgba(30,130,190,0.35)" : "rgba(180,210,230,0.5)"}
                strokeWidth={isGoal ? 1.2 : 0.8}
                strokeDasharray={isGoal ? undefined : "2 4"}/>
              <text x={PAD.left - 8} y={gy + 4} fontSize="10"
                fill={isGoal ? "#1A6FA8" : "#8BAFC8"}
                fontFamily="Inter" textAnchor="end"
                fontWeight={isGoal ? "700" : "400"}>{label}</text>
            </g>
          );
        })}

        {/* ── Layer 5: X-axis ── */}
        <line x1={PAD.left} y1={axisY} x2={PAD.left + CW} y2={axisY}
          stroke="rgba(180,210,230,0.8)" strokeWidth="1"/>
        {xTicks.map(({ t, label }) => {
          const [tx] = toPx(t, START_AMT);
          const isGoal = t === MONTHS;
          return (
            <g key={t}>
              <line x1={tx} y1={axisY} x2={tx} y2={axisY + 4}
                stroke="rgba(160,195,220,0.8)" strokeWidth="1"/>
              <text x={tx} y={axisY + 16} fontSize="10"
                fill={isGoal ? "#1A6FA8" : "#8BAFC8"}
                fontFamily="Inter" textAnchor="middle"
                fontWeight={isGoal ? "700" : "400"}>{label}</text>
            </g>
          );
        })}

        {/* ── Layer 6: planned course line (dashed nautical chart line) ── */}
        <path d={midPath}
          stroke="#1A6FA8" strokeWidth="1.6" strokeLinecap="round"
          strokeDasharray="9 6"
          fill="none" clipPath="url(#chartClip)"
          style={{
            strokeDashoffset: phase >= 1 ? 0 : ROUTE_LEN,
            transition: phase >= 1 ? "stroke-dashoffset 2.4s cubic-bezier(0.4,0,0.2,1)" : "none",
          }}/>

        {/* ── Layer 7: actual path traveled (solid — shows where user has been) ── */}
        <path d={actualPath}
          stroke={status.color} strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round"
          fill="none" clipPath="url(#chartClip)"
          filter="url(#actualGlow)"
          style={{
            opacity: phase >= 2 ? 1 : 0,
            transition: "opacity 0.6s ease",
          }}/>

        {/* ── Layer 8: "현재" vertical marker ── */}
        <line x1={boatX} y1={PAD.top} x2={boatX} y2={axisY}
          stroke="rgba(130,160,190,0.45)" strokeWidth="1" strokeDasharray="3 5"/>
        <text x={boatX} y={axisY + 16} fontSize="10"
          fill="#1A6FA8" fontFamily="Noto Sans KR, sans-serif"
          textAnchor="middle" fontWeight="600">현재</text>

        {/* ── Layer 9: goal island + lighthouse ── */}
        <g transform={`translate(${goalX}, ${goalY})`}
          style={{ opacity: 0, animation: "fadeUp 0.6s ease 1.6s forwards" }}>
          {/* Beam sweep */}
          <path d="M0 -48 L28 -20 L18 -10 Z"
            fill="#4F58FF" fillOpacity="0.08"
            className="animate-lighthouse-beam"/>
          <path d="M0 -48 L-28 -20 L-18 -10 Z"
            fill="#4F58FF" fillOpacity="0.06"
            className="animate-lighthouse-beam"
            style={{ animationDelay: "1.5s" }}/>
          {/* Island terrain */}
          <path d="M-26 2 Q-18 -8 -8 -16 Q0 -22 8 -16 Q18 -8 26 2 Z"
            fill="#2A5E35" fillOpacity="0.9"/>
          <path d="M-12 2 Q-4 -12 0 -18 Q4 -12 12 2 Z"
            fill="#3D7A4A" fillOpacity="0.7"/>
          <ellipse cx="0" cy="4" rx="28" ry="6" fill="#2A5E35" fillOpacity="0.45"/>
          {/* Lighthouse tower */}
          <rect x="-3.5" y="-50" width="7" height="34" rx="1.5" fill="white" fillOpacity="0.95"/>
          <rect x="-3.5" y="-42" width="7" height="7" fill="#D14040" fillOpacity="0.7"/>
          <rect x="-3.5" y="-28" width="7" height="7" fill="#D14040" fillOpacity="0.6"/>
          {/* Lantern room */}
          <rect x="-5" y="-60" width="10" height="11" rx="2" fill="#4F58FF"/>
          <rect x="-4" y="-59" width="8" height="9" rx="1.5" fill="#7E8BFF" fillOpacity="0.5"/>
          {/* Light source */}
          <circle cx="0" cy="-54.5" r="3" fill="white" className="animate-lighthouse"/>
          <circle cx="0" cy="-54.5" r="14" fill="#4F58FF" fillOpacity="0.08"
            className="animate-lighthouse-beam"/>
          {/* Goal label */}
          <rect x="-40" y="-80" width="80" height="17" rx="6" fill="#1A3A8F" fillOpacity="0.9"/>
          <text x="0" y="-68" fontSize="9.5" fontWeight="700" fill="white"
            fontFamily="Noto Sans KR, sans-serif" textAnchor="middle">3,000만원</text>
        </g>

        {/* ── Layer 10: boat — hard-clipped to plot area, pure SVG transforms only ── */}
        {/* clipPath is the final guarantee; clamped position keeps boat visually centered */}
        <g clipPath="url(#chartClip)">
          <g transform={`translate(${boatX}, ${boatY})`}>

            {/* Deviation line from actual position to planned route */}
            {Math.abs(midAtCurrentY - boatY) > 3 && (
              <line x1="0" y1="0" x2="0" y2={midAtCurrentY - boatY}
                stroke={status.color} strokeWidth="1.2" strokeOpacity="0.45"
                strokeDasharray="2.5 2.5"/>
            )}

            {/* Halo */}
            <circle cx="0" cy="0" r="14" fill={status.color} fillOpacity="0.10"/>

            {/* Boat — small, contained, pure SVG rotate only */}
            {/* Mast extends max 18 units up; hull max 8 down; wake max 18 left; sail max 14 right */}
            <g transform={`rotate(${boatAngleDeg})`}>
              {/* Wake */}
              <path d="M-18 3 C-12 1.5 -7 3.5 -3 2.5"
                stroke="white" strokeWidth="1" strokeOpacity="0.45"
                fill="none" strokeLinecap="round"/>
              {/* Hull */}
              <path d="M-12 0 Q-4 6 14 3 L11 8 Q0 11 -10 8 Z" fill="#112244"/>
              {/* Waterline */}
              <line x1="-10" y1="1" x2="12" y2="3"
                stroke="white" strokeWidth="1.2" strokeOpacity="0.55"/>
              {/* Cabin */}
              <rect x="-1" y="-3" width="8" height="3" rx="1" fill="#1B3566"/>
              {/* Mast */}
              <line x1="3" y1="-18" x2="3" y2="1"
                stroke="#0D1E40" strokeWidth="1.1"/>
              {/* Main sail */}
              <path d="M3 -17 L14 1 L3 1 Z"
                fill="white" fillOpacity="0.88"/>
              {/* Jib */}
              <path d="M3 -13 L-10 1 L3 1 Z"
                fill="white" fillOpacity="0.60"/>
              {/* Flag */}
              <path d="M3 -18 L9 -15 L3 -12 Z" fill={status.color} fillOpacity="0.9"/>
            </g>

            {/* Badge — positioned ABOVE the boat so it never pushes toward bottom axis */}
            {/* Vertically clamped so it doesn't exit the top either */}
            <g transform={`translate(0, ${Math.max(-boatY + PAD.top + 8, -34)})`}>
              <rect x="-38" y="-7" width="76" height="24" rx="6"
                fill="white" stroke={status.color} strokeWidth="1.3"/>
              <text x="0" y="2" fontSize="8" fill="#9CA3AF"
                fontFamily="Noto Sans KR, sans-serif" textAnchor="middle">실제 저축액</text>
              <text x="0" y="12" fontSize="10" fontWeight="700"
                fill={status.color} fontFamily="Inter" textAnchor="middle">
                {(ACTUAL_AMT / 10_000).toLocaleString("ko-KR")}만원
              </text>
            </g>
          </g>
        </g>

        {/* ── Desktop hover interaction ── */}
        <rect
          x={PAD.left}
          y={PAD.top}
          width={CW}
          height={CH}
          fill="transparent"
          pointerEvents="all"
          onMouseMove={(event) => {
            const rect = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
            if (!rect) return;
            const svgX = ((event.clientX - rect.left) / rect.width) * W;
            const nextT = Math.max(0, Math.min(MONTHS, Math.round(((svgX - PAD.left) / CW) * MONTHS)));
            setHoveredT(nextT);
          }}
        />

        {hoveredPoint && (
          <g pointerEvents="none">
            <line x1={hoverX} y1={PAD.top} x2={hoverX} y2={axisY}
              stroke="#4F58FF" strokeWidth="1" strokeOpacity="0.3" strokeDasharray="3 4"/>
            <circle cx={hoverX} cy={hoverY} r="4" fill="white" stroke="#1A6FA8" strokeWidth="2"/>
            {hoveredActual !== undefined && (() => {
              const [, actualY] = toPx(hoveredPoint.t, hoveredActual);
              return <circle cx={hoverX} cy={actualY} r="4" fill="white" stroke={status.color} strokeWidth="2"/>;
            })()}

            <g filter="url(#tooltipShadow)">
              <rect x={tooltipX} y={tooltipY} width={tooltipWidth} height={tooltipHeight}
                rx="10" fill="white" stroke="#E8EAEF"/>
              <text x={tooltipX + 12} y={tooltipY + 19} fontSize="11" fontWeight="700"
                fill="#111827" fontFamily="Noto Sans KR, sans-serif">
                {hoveredPoint.t === 0 ? "시작 시점" : `${hoveredPoint.t}개월 후`}
              </text>
              <line x1={tooltipX + 12} y1={tooltipY + 28} x2={tooltipX + tooltipWidth - 12} y2={tooltipY + 28}
                stroke="#F0F1F5"/>
              <text x={tooltipX + 12} y={tooltipY + 46} fontSize="10" fill="#7C8594"
                fontFamily="Noto Sans KR, sans-serif">계획 기준</text>
              <text x={tooltipX + tooltipWidth - 12} y={tooltipY + 46} fontSize="10" fontWeight="700"
                fill="#111827" fontFamily="Noto Sans KR, sans-serif" textAnchor="end">
                {formatManwon(hoveredPoint.mid)}만원
              </text>
              <text x={tooltipX + 12} y={tooltipY + 63} fontSize="10" fill="#7C8594"
                fontFamily="Noto Sans KR, sans-serif">정상 범위</text>
              <text x={tooltipX + tooltipWidth - 12} y={tooltipY + 63} fontSize="10" fontWeight="600"
                fill="#374151" fontFamily="Noto Sans KR, sans-serif" textAnchor="end">
                {formatManwon(hoveredPoint.lower)}–{formatManwon(hoveredPoint.upper)}만원
              </text>
              {hoveredActual !== undefined && (
                <>
                  <text x={tooltipX + 12} y={tooltipY + 80} fontSize="10" fill="#7C8594"
                    fontFamily="Noto Sans KR, sans-serif">실제 저축액</text>
                  <text x={tooltipX + tooltipWidth - 12} y={tooltipY + 80} fontSize="10" fontWeight="700"
                    fill={status.color} fontFamily="Noto Sans KR, sans-serif" textAnchor="end">
                    {formatManwon(hoveredActual)}만원
                  </text>
                </>
              )}
            </g>
          </g>
        )}
      </svg>
    </div>
  );
}

// ── Dashboard ────────────────────────────────────────────────────
export default function Screen08Dashboard({ onNavigate, showAlert = false, monthlyAlert = false }: {
  onNavigate: (s: number) => void; showAlert?: boolean; monthlyAlert?: boolean;
}) {
  const used      = 430_000;
  const limit     = 750_000;
  const remaining = limit - used;
  const usedPct   = Math.round((used / limit) * 100);

  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <AppHeader goalName="독립자금" onNavigate={onNavigate}/>

      <main className="mx-auto max-w-[1280px] px-5 py-6 sm:px-8 sm:py-8">
        {showAlert && (
          <div className="mb-6">
            <AlertBanner
              variant="warning"
              icon={<IconWarning size={15}/>}
              title="이번 달 소비 속도가 계획보다 빨라요"
              description="장기 목표는 계획보다 앞서고 있지만, 이번 달 지출 속도는 높아요. 현재 상황을 반영해 계획을 다시 확인해보세요."
              action="계획 다시 계산"
              onAction={() => onNavigate(10)}
            />
          </div>
        )}

        {monthlyAlert && (
          <div className="mb-6 flex flex-col gap-4 rounded-[18px] border border-[#C7CAFF] bg-[#EEF0FF] px-5 py-4 sm:flex-row sm:items-center">
            <div className="flex min-w-0 flex-1 items-start gap-3">
              <span className="mt-0.5 flex-shrink-0 text-[20px]">📅</span>
              <div>
                <p className="text-[14px] font-bold text-[#3840E0]">9월 계획이 새로 계산되었습니다.</p>
                <p className="mt-0.5 text-[13px] leading-relaxed text-[#5961A8]">지난달 소비와 현재 목표 상황을 반영해 이번 달 계획을 다시 계산했습니다.</p>
              </div>
            </div>
            <div className="flex flex-shrink-0 items-center gap-3">
              <div className="rounded-[10px] border border-[#E8EAEF] bg-white px-3.5 py-2 text-center">
                <p className="text-[10px] text-[#7C8594]">현재 계획</p>
                <p className="num text-[13px] font-bold text-[#111827]">월 75만원</p>
              </div>
              <Button onClick={() => onNavigate(12)} className="h-12 rounded-[12px] py-0">새 계획 확인하기</Button>
            </div>
          </div>
        )}

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
          {/* ── Left (8 cols) ── */}
          <div className="flex flex-col gap-6 lg:col-span-8">

            {/* Goal progress */}
            <div className="bg-white rounded-[20px] border border-[#E8EAEF] p-7">
              <div className="flex items-start justify-between mb-5">
                <div>
                  <p className="text-[13px] font-semibold text-[#7C8594] mb-1.5">독립자금 목표</p>
                  <div className="flex items-baseline gap-2">
                    <span className="num text-[40px] font-bold text-[#111827] leading-none">
                      {Math.round(ACTUAL_AMT / 10000).toLocaleString("ko-KR")}만원
                    </span>
                    <span className="text-[#C0C7D0] text-[20px]">/</span>
                    <span className="num text-[20px] font-semibold text-[#7C8594]">3,000만원</span>
                  </div>
                </div>
                <div className="text-right">
                  <p className="text-[12px] text-[#9CA3AF] mb-1">목표일까지</p>
                  <p className="num text-[32px] font-bold text-[#4F58FF] leading-none">
                    {MONTHS - CURRENT_T}
                    <span className="text-[16px] font-semibold text-[#9CA3AF] ml-1">개월</span>
                  </p>
                </div>
              </div>
              <GoalProgressBar current={ACTUAL_AMT} total={GOAL_AMT} showLabels={false}/>
              <p className="num mt-2.5 text-[12px] font-semibold text-[#4F58FF]">목표의 39.3%를 모았어요</p>
            </div>

            {/* Monthly spending status */}
            <div className="rounded-[20px] border border-[#E8EAEF] bg-white p-6">
              <div className="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
                <div>
                  <p className="text-[13px] font-semibold text-[#7C8594]">이번 달 더 쓸 수 있는 금액</p>
                  <p className="num mt-2 text-[36px] font-bold leading-none tracking-tight text-[#4F58FF]">
                    {remaining.toLocaleString("ko-KR")}<span className="ml-1 text-[16px] font-semibold text-[#7B82FF]">원</span>
                  </p>
                  <p className="mt-2 text-[12px] text-[#7C8594]">이번 달 말까지 권장하는 남은 생활비예요.</p>
                </div>
                <div className="grid grid-cols-2 gap-6 sm:text-right">
                  <div>
                    <p className="text-[11px] text-[#7C8594]">이번 달 한도</p>
                    <p className="num mt-1 text-[15px] font-bold text-[#111827]">{limit.toLocaleString("ko-KR")}원</p>
                  </div>
                  <div>
                    <p className="text-[11px] text-[#7C8594]">현재 사용</p>
                    <p className="num mt-1 text-[15px] font-bold text-[#111827]">{used.toLocaleString("ko-KR")}원</p>
                  </div>
                </div>
              </div>
              <div className="mt-5 h-2 w-full overflow-hidden rounded-full bg-[#EEF0FF]">
                <div className="h-full rounded-full bg-[#4F58FF]" style={{ width: `${usedPct}%` }}/>
              </div>
              <p className="mt-2 text-[11px] text-[#7C8594]">한도의 {usedPct}%를 사용했어요</p>
            </div>

            {/* Route chart card */}
            <div className="bg-white rounded-[20px] border border-[#E8EAEF] p-7">
              <RouteChart onNavigate={onNavigate}/>
            </div>
          </div>

          {/* ── Right sidebar (4 cols) ── */}
          <div className="flex flex-col gap-5 lg:col-span-4">

            {/* Current plan */}
            <div className="bg-white rounded-[20px] border border-[#E8EAEF] p-6">
              <div className="flex items-center justify-between mb-5">
                <p className="text-[13px] font-bold text-[#111827]">현재 계획 · 균형형</p>
                <span className="rounded-full bg-[#EEF0FF] px-2.5 py-1 text-[10px] font-bold text-[#3840E0]">진행 중</span>
              </div>
              <div className="flex flex-col gap-5">
                <div>
                  <p className="text-[12px] text-[#6B7280] mb-2">계획 안정성</p>
                  <div className="flex items-center gap-3">
                    <p className="num text-[26px] font-bold text-[#0DB97A] leading-none">80%</p>
                    <div className="flex-1 bg-[#F0F1F5] rounded-full h-2 overflow-hidden">
                      <div className="h-full rounded-full bg-[#0DB97A]" style={{ width: "80%" }}/>
                    </div>
                  </div>
                </div>
                <div className="border-t border-[#F0F1F5] pt-4 space-y-2.5">
                  {[
                    { k: "목표일",   v: "2028년 1월" },
                    { k: "남은 기간", v: `${MONTHS - CURRENT_T}개월` },
                    { k: "필요 저축", v: "월 800,000원" },
                  ].map(({ k, v }) => (
                    <div key={k} className="flex justify-between text-[13px]">
                      <span className="text-[#9CA3AF]">{k}</span>
                      <span className="font-semibold text-[#111827]">{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* Actions */}
            <div className="flex flex-col gap-2.5">
              <Button variant="secondary" className="w-full" onClick={() => onNavigate(9)}>
                나의 계획 정보 수정하기
              </Button>
              <Button variant="outline" className="w-full" onClick={() => onNavigate(10)}>
                현재 시점 기준으로 다시 계산
              </Button>
            </div>

            {/* Planned expenses */}
            <div className="bg-white rounded-[20px] border border-[#E8EAEF] p-5">
              <p className="text-[11px] font-bold text-[#9CA3AF] uppercase tracking-wider mb-4">예정 지출</p>
              <div className="flex flex-col gap-2">
                {[
                  { name: "일본 여행",   date: "2026.11.15", amount: 1_200_000 },
                  { name: "노트북 구매", date: "2027.02.10", amount: 1_500_000 },
                ].map(exp => (
                  <div key={exp.name}
                    className="flex items-center justify-between py-2 border-b border-[#F5F6F9] last:border-0">
                    <div>
                      <p className="text-[13px] font-semibold text-[#111827]">{exp.name}</p>
                      <p className="text-[11px] text-[#9CA3AF] mt-0.5">{exp.date}</p>
                    </div>
                    <span className="num text-[13px] font-bold text-[#6B7280]">
                      {(exp.amount / 10_000).toFixed(0)}만원
                    </span>
                  </div>
                ))}
              </div>
              <p className="text-[11px] text-[#9CA3AF] mt-3 leading-relaxed">
                예정 지출은 계획 경로에 반영되어 있습니다.
              </p>
            </div>

          </div>
        </div>
      </main>
    </div>
  );
}
