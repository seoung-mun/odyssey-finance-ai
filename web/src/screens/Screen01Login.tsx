// Screen 1 — Login
export default function Screen01Login({ onNext }: { onNext: () => void }) {
  return (
    <div className="relative min-h-screen overflow-hidden bg-[#0B3B62] lg:flex lg:bg-white">

      {/* ── Left: login panel ── */}
      <div className="relative z-10 flex min-h-screen w-full items-center justify-center px-5 py-5 sm:px-8 sm:py-8 lg:w-[520px] lg:flex-shrink-0 lg:items-stretch lg:p-0">
        <div className="flex min-h-[calc(100vh-40px)] w-full max-w-[430px] flex-col justify-between rounded-[28px] border border-white/70 bg-white/[0.96] px-8 py-8 shadow-[0_24px_80px_rgba(5,35,59,0.22)] backdrop-blur-xl sm:min-h-[calc(100vh-64px)] sm:px-10 sm:py-10 lg:min-h-screen lg:max-w-none lg:rounded-none lg:border-0 lg:bg-white lg:px-14 lg:py-12 lg:shadow-none lg:backdrop-blur-none">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-[11px] bg-[#4F58FF] shadow-lg shadow-[#4F58FF]/25">
              <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
                <path d="M9 1.5L16 5.5v7L9 16.5 2 12.5v-7L9 1.5z" fill="white" fillOpacity="0.85"/>
                <circle cx="9" cy="9" r="2.5" fill="white"/>
              </svg>
            </div>
            <span className="text-[20px] font-bold tracking-tight text-[#111827]">Odyssey</span>
          </div>

          <div className="flex flex-col gap-8">
            <div>
              <p className="mb-4 text-[12px] font-bold tracking-[0.12em] text-[#4F58FF]">목표 기반 금융 플래너</p>
              <h1 className="mb-5 text-[38px] font-bold leading-[1.13] tracking-[-0.035em] text-[#111827] sm:text-[40px] lg:text-[42px]">
                오늘의 돈에서<br/>
                원하는 미래까지.
              </h1>
              <p className="max-w-[340px] text-[15px] leading-[1.75] text-[#5F6877]">
                수입과 소비를 연결하면, 매달 쓸 수 있는 금액과<br className="hidden sm:block"/>
                목표까지의 경로를 한눈에 보여드려요.
              </p>
            </div>

            <div className="flex flex-col gap-3.5">
              <button
                onClick={onNext}
                className="group flex w-full items-center justify-center gap-3 rounded-[14px] bg-[#4F58FF] px-6 py-4 text-[14px] font-bold text-white shadow-[0_10px_24px_rgba(79,88,255,0.24)] transition-all hover:-translate-y-0.5 hover:bg-[#424BE8] hover:shadow-[0_14px_30px_rgba(79,88,255,0.3)] active:translate-y-0 cursor-pointer"
              >
                <span className="flex h-6 w-6 items-center justify-center rounded-full bg-white">
                  <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                    <path d="M18.17 10.21c0-.69-.06-1.35-.17-1.98H10v3.74h4.58a3.91 3.91 0 01-1.7 2.57v2.14h2.75c1.61-1.48 2.54-3.66 2.54-6.47z" fill="#4285F4"/>
                    <path d="M10 18.5c2.3 0 4.23-.76 5.64-2.06l-2.75-2.14c-.76.51-1.73.81-2.89.81-2.22 0-4.11-1.5-4.78-3.52H2.37v2.21A8.5 8.5 0 0010 18.5z" fill="#34A853"/>
                    <path d="M5.22 11.59a5.1 5.1 0 010-3.18V6.2H2.37a8.5 8.5 0 000 7.6l2.85-2.21z" fill="#FBBC05"/>
                    <path d="M10 4.88c1.25 0 2.37.43 3.26 1.28l2.44-2.44A8.5 8.5 0 002.37 6.2l2.85 2.21C5.89 6.38 7.78 4.88 10 4.88z" fill="#EA4335"/>
                  </svg>
                </span>
                Google로 계획 시작하기
              </button>

              <div className="flex items-center justify-center gap-1.5 text-[12px] font-medium text-[#667085]">
                <svg width="13" height="13" viewBox="0 0 13 13" fill="none" aria-hidden="true">
                  <rect x="2.25" y="5.25" width="8.5" height="6" rx="1.5" stroke="#667085" strokeWidth="1.2"/>
                  <path d="M4.25 5.25V3.9a2.25 2.25 0 014.5 0v1.35" stroke="#667085" strokeWidth="1.2" strokeLinecap="round"/>
                </svg>
                금융정보는 계획 계산에만 사용해요
              </div>
            </div>
          </div>

          <div className="flex flex-col gap-3 text-[11px] leading-relaxed text-[#8A93A3] sm:flex-row sm:items-center sm:justify-between">
            <p>
              계속하면 이용약관 및 개인정보처리방침에 동의합니다.
            </p>
            <p className="whitespace-nowrap text-[#A9B0BC]">© 2026 Odyssey</p>
          </div>
        </div>
      </div>

      {/* ── Right: daytime ocean hero ── */}
      <div className="absolute inset-0 overflow-hidden lg:relative lg:inset-auto lg:flex-1">
        <svg
          viewBox="0 0 960 640"
          preserveAspectRatio="xMaxYMid slice"
          style={{ position: "absolute", inset: 0, width: "100%", height: "100%", display: "block" }}
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <defs>
            {/* Sky — clear daytime blue */}
            <linearGradient id="skyDay" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="#7AB8D8"/>
              <stop offset="38%"  stopColor="#9ECCE8"/>
              <stop offset="68%"  stopColor="#BDDFF4"/>
              <stop offset="88%"  stopColor="#D6EEF9"/>
              <stop offset="100%" stopColor="#E8F5FB"/>
            </linearGradient>
            {/* Sunlight glow — upper right warmth */}
            <radialGradient id="sunGlow" cx="78%" cy="8%" r="55%">
              <stop offset="0%"   stopColor="#FFF8E0" stopOpacity="0.55"/>
              <stop offset="50%"  stopColor="#E8F3FF" stopOpacity="0.20"/>
              <stop offset="100%" stopColor="#B8D8F0" stopOpacity="0"/>
            </radialGradient>
            {/* Horizon haze */}
            <radialGradient id="horizonHaze" cx="50%" cy="100%" r="65%">
              <stop offset="0%"   stopColor="#C8E8F8" stopOpacity="0.70"/>
              <stop offset="100%" stopColor="#9ECCE8"  stopOpacity="0"/>
            </radialGradient>
            {/* Ocean base gradient */}
            <linearGradient id="oceanDay" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="#3AA8D0"/>
              <stop offset="22%"  stopColor="#2282B2"/>
              <stop offset="55%"  stopColor="#145C8A"/>
              <stop offset="100%" stopColor="#093860"/>
            </linearGradient>
            {/* Horizon water shimmer band */}
            <linearGradient id="shimmer" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="#68C4E8" stopOpacity="0.60"/>
              <stop offset="100%" stopColor="#3AA8D0" stopOpacity="0"/>
            </linearGradient>
            {/* Sail shadow filter for contrast on sky */}
            <filter id="sailShadow" x="-20%" y="-20%" width="140%" height="140%">
              <feDropShadow dx="0" dy="1" stdDeviation="1.5" floodColor="#1A4A70" floodOpacity="0.20"/>
            </filter>
            {/* Island soft edge */}
            <filter id="islandBlur" x="-10%" y="-10%" width="120%" height="120%">
              <feGaussianBlur stdDeviation="0.6"/>
            </filter>
          </defs>

          {/* ── Sky ── */}
          <rect x="0" y="0" width="960" height="328" fill="url(#skyDay)"/>
          {/* Sun warmth overlay */}
          <rect x="0" y="0" width="960" height="328" fill="url(#sunGlow)"/>
          {/* Horizon atmospheric haze */}
          <ellipse cx="480" cy="328" rx="720" ry="110" fill="url(#horizonHaze)"/>

          {/* ── Clouds — horizontal, soft, minimal ── */}
          {/* Cloud 1 — left */}
          <g opacity="0.82" style={{ animation: "waveDrift 90s linear infinite" }}>
            <path d="M40 88 Q90 72 148 82 Q178 70 210 82 Q245 74 280 84 Q240 100 175 97 Q115 100 40 88Z"
              fill="white"/>
            <path d="M55 92 Q100 80 148 88" stroke="white" strokeWidth="1" strokeOpacity="0.4" fill="none"/>
          </g>
          {/* Cloud 2 — right */}
          <g opacity="0.72" style={{ animation: "waveDrift 110s linear infinite", animationDelay: "-20s" }}>
            <path d="M620 115 Q672 100 730 110 Q762 100 800 112 Q840 104 875 114 Q830 128 766 126 Q700 128 620 115Z"
              fill="white"/>
          </g>
          {/* Cloud 3 — mid (smaller) */}
          <g opacity="0.55" style={{ animation: "waveDrift 75s linear infinite", animationDelay: "-35s" }}>
            <path d="M340 160 Q378 150 420 160 Q445 153 468 162 Q435 172 392 170 Q358 172 340 160Z"
              fill="white"/>
          </g>
          {/* Cloud 4 — far right, faint */}
          <g opacity="0.40" style={{ animation: "waveDrift 95s linear infinite", animationDelay: "-50s" }}>
            <path d="M780 200 Q810 193 845 200 Q862 195 880 202 Q858 210 826 208 Q800 210 780 200Z"
              fill="white"/>
          </g>

          {/* ── Horizon line ── */}
          <line x1="0" y1="328" x2="960" y2="328"
            stroke="#B8DCEA" strokeWidth="0.8" strokeOpacity="0.9"/>

          {/* ── Island — fixed destination on the horizon ── */}
          {/* "섬 = 사용자가 향하는 목표" */}
          <g transform="translate(808, 328)">
            {/* Island body — soft hill silhouette */}
            <path d="M-48 2 Q-32 -26 -10 -38 Q6 -46 18 -40 Q34 -28 50 2 Z"
              fill="#2E5A38" filter="url(#islandBlur)"/>
            {/* Lighter vegetation highlight */}
            <path d="M-28 0 Q-16 -22 0 -32 Q14 -22 26 0 Z"
              fill="#3D7248" fillOpacity="0.7"/>
            {/* Shoreline tint */}
            <path d="M-52 4 Q0 -2 52 4 Q32 10 0 12 Q-32 10 -52 4Z"
              fill="#2E5A38" fillOpacity="0.5"/>

            {/* ── Tiny lighthouse on the island peak ── */}
            {/* Tower */}
            <rect x="-3" y="-60" width="6" height="20" rx="1" fill="white" fillOpacity="0.92"/>
            {/* Red stripe */}
            <rect x="-2.8" y="-52" width="5.6" height="5" fill="#E05050" fillOpacity="0.65"/>
            {/* Lantern room */}
            <rect x="-4.5" y="-68" width="9" height="9" rx="1.5" fill="#4F58FF"/>
            {/* Light source — pulsing */}
            <circle cx="0" cy="-64" r="2.5" fill="white" fillOpacity="0.95"
              className="animate-lighthouse"/>
            {/* Beam glow — subtle */}
            <circle cx="0" cy="-64" r="14" fill="white" fillOpacity="0.06"
              className="animate-lighthouse-beam"/>
            {/* Lighthouse base */}
            <rect x="-4" y="-40" width="8" height="2" rx="1" fill="white" fillOpacity="0.5"/>
          </g>

          {/* ── Ocean ── */}
          <rect x="0" y="328" width="960" height="312" fill="url(#oceanDay)"/>
          {/* Surface shimmer band just below horizon */}
          <rect x="0" y="328" width="960" height="28" fill="url(#shimmer)"/>

          {/* Light path on water toward island — subtle sun/lighthouse reflection */}
          <path d="M808 340 L750 380 L866 380 Z"
            fill="white" fillOpacity="0.04"/>
          <path d="M808 345 L720 420 L896 420 Z"
            fill="white" fillOpacity="0.025"/>

          {/* ── Wave layers — calm, rhythmic ── */}

          {/* Wave 1 — distant, near horizon */}
          <g className="animate-wave-drift"
            style={{ animationDuration: "34s", willChange: "transform" }}>
            <path
              d="M-40 344 C24 339 88 349 152 344 C216 339 280 349 344 344 C408 339 472 349 536 344 C600 339 664 349 728 344 C792 339 856 349 920 344 C984 339 1048 349 1112 344 C1176 339 1240 349 1304 344 C1368 339 1432 349 1496 344 C1560 339 1624 349 1688 344 C1752 339 1816 349 1880 344 C1944 339 2008 349 1960 344"
              stroke="#68C0DC" strokeWidth="0.6" strokeOpacity="0.45" fill="none"/>
          </g>

          {/* Wave 2 */}
          <g className="animate-wave-drift"
            style={{ animationDuration: "26s", animationDelay: "-8s", willChange: "transform" }}>
            <path
              d="M-40 360 C32 354 104 366 176 360 C248 354 320 366 392 360 C464 354 536 366 608 360 C680 354 752 366 824 360 C896 354 968 366 1040 360 C1112 354 1184 366 1256 360 C1328 354 1400 366 1472 360 C1544 354 1616 366 1688 360 C1760 354 1832 366 1904 360 C1976 354 2000 366 1960 360"
              stroke="#50A8CC" strokeWidth="0.8" strokeOpacity="0.40" fill="none"/>
            <path
              d="M-40 360 C32 354 104 366 176 360 C248 354 320 366 392 360 C464 354 536 366 608 360 C680 354 752 366 824 360 C896 354 968 366 1040 360 C1112 354 1184 366 1256 360 C1328 354 1400 366 1472 360 C1544 354 1616 366 1688 360 C1760 354 1832 366 1904 360 C1976 354 2000 366 1960 360 L1960 640 L-40 640 Z"
              fill="#2280B0" fillOpacity="0.12"/>
          </g>

          {/* Wave 3 — mid */}
          <g className="animate-wave-drift"
            style={{ animationDuration: "19s", animationDelay: "-4s", willChange: "transform" }}>
            <path
              d="M-40 392 C52 382 144 402 236 392 C328 382 420 402 512 392 C604 382 696 402 788 392 C880 382 972 402 1064 392 C1156 382 1248 402 1340 392 C1432 382 1524 402 1616 392 C1708 382 1800 402 1892 392 C1960 382 2000 402 1960 392"
              stroke="#3A94BC" strokeWidth="1.0" strokeOpacity="0.38" fill="none"/>
            <path
              d="M-40 392 C52 382 144 402 236 392 C328 382 420 402 512 392 C604 382 696 402 788 392 C880 382 972 402 1064 392 C1156 382 1248 402 1340 392 C1432 382 1524 402 1616 392 C1708 382 1800 402 1892 392 C1960 382 2000 402 1960 392 L1960 640 L-40 640 Z"
              fill="#1670A0" fillOpacity="0.18"/>
          </g>

          {/* Wave 4 — nearer */}
          <g className="animate-wave-drift"
            style={{ animationDuration: "14s", animationDelay: "-3s", willChange: "transform" }}>
            <path
              d="M-40 440 C70 426 180 454 290 440 C400 426 510 454 620 440 C730 426 840 454 950 440 C1060 426 1170 454 1280 440 C1390 426 1500 454 1610 440 C1720 426 1830 454 1940 440 C1960 426 2000 454 1960 440"
              stroke="#2878A8" strokeWidth="1.2" strokeOpacity="0.40" fill="none"/>
            <path
              d="M-40 440 C70 426 180 454 290 440 C400 426 510 454 620 440 C730 426 840 454 950 440 C1060 426 1170 454 1280 440 C1390 426 1500 454 1610 440 C1720 426 1830 454 1940 440 C1960 426 2000 454 1960 440 L1960 640 L-40 640 Z"
              fill="#105080" fillOpacity="0.28"/>
          </g>

          {/* Wave 5 — foreground */}
          <g className="animate-wave-drift"
            style={{ animationDuration: "10s", animationDelay: "-1s", willChange: "transform" }}>
            <path
              d="M-40 508 C100 488 240 528 380 508 C520 488 660 528 800 508 C940 488 1080 528 1220 508 C1360 488 1500 528 1640 508 C1780 488 1920 528 1960 508"
              stroke="#1C6090" strokeWidth="1.4" strokeOpacity="0.42" fill="none"/>
            <path
              d="M-40 508 C100 488 240 528 380 508 C520 488 660 528 800 508 C940 488 1080 528 1220 508 C1360 488 1500 528 1640 508 C1780 488 1920 528 1960 508 L1960 640 L-40 640 Z"
              fill="#083050" fillOpacity="0.40"/>
          </g>

          {/* Wave 6 — closest */}
          <g className="animate-wave-drift"
            style={{ animationDuration: "7s", animationDelay: "-2s", willChange: "transform" }}>
            <path
              d="M-40 576 C120 554 300 598 480 576 C660 554 840 598 1020 576 C1200 554 1380 598 1560 576 C1740 554 1920 598 1960 576"
              stroke="#124870" strokeWidth="1.6" strokeOpacity="0.45" fill="none"/>
            <path
              d="M-40 576 C120 554 300 598 480 576 C660 554 840 598 1020 576 C1200 554 1380 598 1560 576 C1740 554 1920 598 1960 576 L1960 640 L-40 640 Z"
              fill="#061828" fillOpacity="0.55"/>
          </g>

          {/* ── Ship — traverses toward the island ── */}
          {/* "배 = 현재의 사용자" */}
          {/* Outer g: horizontal traversal via SMIL (SVG user units, not CSS px) */}
          <g>
            <animateTransform
              attributeName="transform"
              type="translate"
              from="260 340"
              to="800 334"
              dur="52s"
              repeatCount="indefinite"
            />
            {/* Inner g: gentle vertical float */}
            <g transform="scale(1.5)"
              className="animate-boat-float"
              style={{ transformOrigin: "0 0" }}>

              {/* Wake — V-shaped light trails behind the ship */}
              <path d="M-62 3 Q-44 0 -28 4" stroke="white" strokeWidth="1"
                strokeOpacity="0.30" fill="none" strokeLinecap="round"/>
              <path d="M-68 7 Q-48 4.5 -30 8" stroke="white" strokeWidth="0.7"
                strokeOpacity="0.18" fill="none" strokeLinecap="round"/>
              <path d="M-72 12 Q-50 9 -32 12.5" stroke="white" strokeWidth="0.5"
                strokeOpacity="0.12" fill="none" strokeLinecap="round"/>
              {/* Foam patch */}
              <ellipse cx="-38" cy="6" rx="22" ry="3.5" fill="white" fillOpacity="0.10"/>

              {/* Hull — elegant side profile */}
              <path d="M-32 3 Q-12 10 32 5 L26 14 Q0 18 -24 14 Z" fill="#152848"/>
              {/* Waterline white stripe */}
              <line x1="-26" y1="3.5" x2="28" y2="5"
                stroke="white" strokeWidth="1.2" strokeOpacity="0.55"/>
              {/* Hull highlight reflection */}
              <path d="M-20 3.8 Q8 2.5 24 5"
                stroke="white" strokeWidth="0.5" strokeOpacity="0.20" fill="none"/>

              {/* Cabin */}
              <rect x="-2" y="-3" width="16" height="6" rx="1.5" fill="#1E3562"/>

              {/* Mast */}
              <line x1="6" y1="-75" x2="6" y2="3"
                stroke="#1A2E55" strokeWidth="1.2" strokeOpacity="0.80"/>
              {/* Spreader */}
              <line x1="-6" y1="-48" x2="18" y2="-48"
                stroke="#1A2E55" strokeWidth="0.7" strokeOpacity="0.55"/>
              {/* Boom */}
              <line x1="6" y1="3" x2="36" y2="1"
                stroke="#1A2E55" strokeWidth="0.8" strokeOpacity="0.65"/>

              {/* Main sail — large, clean triangle */}
              <path d="M6 -73 L34 1 L6 1 Z"
                fill="white" fillOpacity="0.88" filter="url(#sailShadow)"/>
              {/* Subtle sail curvature/fullness */}
              <path d="M6 -55 Q22 -28 30 0 L6 0 Z"
                fill="white" fillOpacity="0.08"/>
              {/* Batten detail lines */}
              <line x1="6" y1="-52" x2="24" y2="-6"
                stroke="#CCE4F0" strokeWidth="0.5" strokeOpacity="0.35"/>
              <line x1="6" y1="-28" x2="28" y2="-1"
                stroke="#CCE4F0" strokeWidth="0.5" strokeOpacity="0.28"/>

              {/* Jib — foresail */}
              <path d="M6 -58 L-22 1 L6 1 Z"
                fill="white" fillOpacity="0.60" filter="url(#sailShadow)"/>

              {/* Flag at masthead — tiny, indigo brand color */}
              <path d="M6 -75 L14 -71 L6 -67 Z" fill="#4F58FF" fillOpacity="0.85"/>
            </g>
          </g>

          {/* Water sparkle highlights — scattered sunlight reflections */}
          {[
            { x: 180, y: 344, r: 1.8 }, { x: 310, y: 356, r: 1.2 },
            { x: 445, y: 348, r: 1.5 }, { x: 590, y: 362, r: 1.0 },
            { x: 680, y: 352, r: 1.4 }, { x: 750, y: 370, r: 0.9 },
            { x: 240, y: 382, r: 1.0 }, { x: 520, y: 375, r: 1.2 },
          ].map((s, i) => (
            <circle key={i} cx={s.x} cy={s.y} r={s.r}
              fill="white"
              style={{
                opacity: 0.35,
                animation: `starTwinkle ${2.5 + (i % 3) * 0.7}s ease-in-out ${i * 0.4}s infinite`,
                ["--star-lo" as string]: 0.15,
                ["--star-hi" as string]: 0.55,
              } as React.CSSProperties}
            />
          ))}
        </svg>
      </div>
    </div>
  );
}
