import { useEffect, useRef, useState } from "react";

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize(config: {
            client_id: string;
            callback: (value: { credential: string }) => void;
          }): void;
          renderButton(element: HTMLElement, options: Record<string, unknown>): void;
        };
      };
    };
  }
}

export const LoginPage = ({
  clientId,
  onCredential,
}: {
  clientId: string;
  onCredential: (credential: string) => Promise<void> | void;
}) => {
  const target = useRef<HTMLDivElement>(null);
  const submitting = useRef(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!clientId) return;
    const render = () => {
      if (!window.google || !target.current) return;
      window.google.accounts.id.initialize({
        client_id: clientId,
        callback: ({ credential }) => {
          if (submitting.current) return;
          submitting.current = true;
          setBusy(true);
          Promise.resolve(onCredential(credential))
            .catch(() => setError("로그인하지 못했습니다. 다시 시도해 주세요."))
            .finally(() => {
              submitting.current = false;
              setBusy(false);
            });
        },
      });
      window.google.accounts.id.renderButton(target.current, {
        theme: "outline",
        size: "large",
        width: 320,
        text: "continue_with",
      });
    };
    if (window.google) render();
    else {
      const script = document.createElement("script");
      script.src = "https://accounts.google.com/gsi/client";
      script.async = true;
      script.onload = render;
      script.onerror = () => setError("Google 로그인 서버에 연결하지 못했습니다.");
      document.head.append(script);
      return () => script.remove();
    }
  }, [clientId, onCredential]);

  return (
    <main className="login-page">
      <section className="login-panel login-panel--white" aria-labelledby="login-title">
        <div className="login-panel-content">
          <div className="odyssey-brand">
            <div className="odyssey-logo-mark">
              <svg viewBox="0 0 18 18" fill="none">
                <path d="M9 1.5 16 5.5v7L9 16.5l-7-4v-7z" fill="white" fillOpacity="0.85" />
                <circle cx="9" cy="9" r="2.5" fill="white" />
              </svg>
            </div>
            <span>Odyssey</span>
          </div>
          <div className="login-copy">
            <p className="eyebrow">목표 기반 금융 플래너</p>
            <h1>
              오늘의 돈에서
              <br />
              원하는 미래까지.
            </h1>
            <p className="lead">
              수입과 소비를 연결하면, 매달 쓸 수 있는 금액과 목표까지의 경로를 한눈에 보여드려요.
            </p>
          </div>
          <div className="login-action">
            <h2 id="login-title">Google로 계획 시작하기</h2>
            <p>Google 계정으로 안전하게 이어서 관리하세요.</p>
            {!clientId ? (
              <p role="alert" className="notice danger">
                Google 로그인을 준비하지 못했습니다. 환경 설정을 확인해 주세요.
              </p>
            ) : (
              <div ref={target} className={busy ? "google-button busy" : "google-button"} />
            )}
            {error && (
              <p role="alert" className="notice danger">
                {error}
              </p>
            )}
          </div>
          <div className="login-footer">
            <p className="login-privacy">금융정보는 계획 계산에만 사용해요.</p>
            <p>계속하면 이용약관 및 개인정보처리방침에 동의합니다.</p>
            <p>© 2026 Odyssey</p>
          </div>
        </div>
      </section>
      <section className="login-hero" aria-hidden="true">
        <svg viewBox="0 0 960 640" preserveAspectRatio="xMaxYMid slice" focusable="false">
          <defs>
            <linearGradient id="login-sky-day" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#7AB8D8" />
              <stop offset="38%" stopColor="#9ECCE8" />
              <stop offset="68%" stopColor="#BDDFF4" />
              <stop offset="88%" stopColor="#D6EEF9" />
              <stop offset="100%" stopColor="#E8F5FB" />
            </linearGradient>
            <radialGradient id="login-sun-glow" cx="78%" cy="8%" r="55%">
              <stop offset="0%" stopColor="#FFF8E0" stopOpacity="0.55" />
              <stop offset="50%" stopColor="#E8F3FF" stopOpacity="0.20" />
              <stop offset="100%" stopColor="#B8D8F0" stopOpacity="0" />
            </radialGradient>
            <radialGradient id="login-horizon-haze" cx="50%" cy="100%" r="65%">
              <stop offset="0%" stopColor="#C8E8F8" stopOpacity="0.70" />
              <stop offset="100%" stopColor="#9ECCE8" stopOpacity="0" />
            </radialGradient>
            <linearGradient id="login-ocean-day" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#3AA8D0" />
              <stop offset="22%" stopColor="#2282B2" />
              <stop offset="55%" stopColor="#145C8A" />
              <stop offset="100%" stopColor="#093860" />
            </linearGradient>
            <linearGradient id="login-shimmer" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#68C4E8" stopOpacity="0.60" />
              <stop offset="100%" stopColor="#3AA8D0" stopOpacity="0" />
            </linearGradient>
            <filter id="login-sail-shadow" x="-20%" y="-20%" width="140%" height="140%">
              <feDropShadow dx="0" dy="1" stdDeviation="1.5" floodColor="#1A4A70" floodOpacity="0.20" />
            </filter>
            <filter id="login-island-blur" x="-10%" y="-10%" width="120%" height="120%">
              <feGaussianBlur stdDeviation="0.6" />
            </filter>
          </defs>
          <rect x="0" y="0" width="960" height="328" fill="url(#login-sky-day)" />
          <rect x="0" y="0" width="960" height="328" fill="url(#login-sun-glow)" />
          <ellipse cx="480" cy="328" rx="720" ry="110" fill="url(#login-horizon-haze)" />
          <g className="login-hero-clouds">
            <g className="login-hero-cloud login-hero-cloud--one">
              <path d="M40 88Q90 72 148 82q30-12 62 0 35-8 70 2-40 16-105 13-60 3-135-9Z" fill="white" />
              <path d="M55 92q45-12 93-4" stroke="white" strokeWidth="1" strokeOpacity="0.4" fill="none" />
            </g>
            <g className="login-hero-cloud login-hero-cloud--two">
              <path d="M620 115q52-15 110-5 32-10 70 2 40-8 75 2-45 14-109 12-66 2-146-11Z" fill="white" />
            </g>
            <g className="login-hero-cloud login-hero-cloud--three">
              <path d="M340 160q38-10 80 0 25-7 48 2-33 10-76 8-34 2-52-10Z" fill="white" />
            </g>
            <g className="login-hero-cloud login-hero-cloud--four">
              <path d="M780 200q30-7 65 0 17-5 35 2-22 8-54 6-26 2-46-8Z" fill="white" />
            </g>
          </g>
          <line x1="0" y1="328" x2="960" y2="328" stroke="#B8DCEA" strokeWidth="0.8" strokeOpacity="0.9" />
          <g className="login-hero-island" transform="translate(808 328)">
            <path d="M-48 2Q-32-26-10-38 6-46 18-40 34-28 50 2Z" fill="#2E5A38" filter="url(#login-island-blur)" />
            <path d="M-28 0Q-16-22 0-32 14-22 26 0Z" fill="#3D7248" fillOpacity="0.7" />
            <path d="M-52 4Q0-2 52 4 32 10 0 12-32 10-52 4Z" fill="#2E5A38" fillOpacity="0.5" />
            <g className="login-hero-lighthouse">
              <rect x="-3" y="-60" width="6" height="20" rx="1" fill="white" fillOpacity="0.92" />
              <rect x="-2.8" y="-52" width="5.6" height="5" fill="#E05050" fillOpacity="0.65" />
              <rect x="-4.5" y="-68" width="9" height="9" rx="1.5" fill="#4F58FF" />
              <circle className="animate-lighthouse" cx="0" cy="-64" r="2.5" fill="white" fillOpacity="0.95" />
              <circle className="animate-lighthouse-beam" cx="0" cy="-64" r="14" fill="white" fillOpacity="0.06" />
              <rect x="-4" y="-40" width="8" height="2" rx="1" fill="white" fillOpacity="0.5" />
            </g>
          </g>
          <rect x="0" y="328" width="960" height="312" fill="url(#login-ocean-day)" />
          <rect x="0" y="328" width="960" height="28" fill="url(#login-shimmer)" />
          <path d="M808 340 750 380h116Z" fill="white" fillOpacity="0.04" />
          <path d="M808 345 720 420h176Z" fill="white" fillOpacity="0.025" />
          <g className="login-hero-wave login-hero-wave--one animate-wave-drift">
            <path d="M-40 344c64-5 128 5 192 0s128 5 192 0 128 5 192 0 128 5 192 0 128 5 192 0 128 5 192 0 128 5 196 0" stroke="#68C0DC" strokeWidth="0.6" strokeOpacity="0.45" fill="none" />
          </g>
          <g className="login-hero-wave login-hero-wave--two animate-wave-drift">
            <path d="M-40 360c72-6 144 6 216 0s144 6 216 0 144 6 216 0 144 6 216 0 144 6 216 0 144 6 216 0 144 6 196 0" stroke="#50A8CC" strokeWidth="0.8" strokeOpacity="0.40" fill="none" />
            <path d="M-40 360c72-6 144 6 216 0s144 6 216 0 144 6 216 0 144 6 216 0 144 6 216 0 144 6 216 0 144 6 196 0v280H-40Z" fill="#2280B0" fillOpacity="0.12" />
          </g>
          <g className="login-hero-wave login-hero-wave--three animate-wave-drift">
            <path d="M-40 392c92-10 184 10 276 0s184 10 276 0 184 10 276 0 184 10 276 0 184 10 276 0 184 10 236 0" stroke="#3A94BC" strokeWidth="1" strokeOpacity="0.38" fill="none" />
            <path d="M-40 392c92-10 184 10 276 0s184 10 276 0 184 10 276 0 184 10 276 0 184 10 276 0 184 10 236 0v248H-40Z" fill="#1670A0" fillOpacity="0.18" />
          </g>
          <g className="login-hero-wave login-hero-wave--four animate-wave-drift">
            <path d="M-40 440c110-14 220 14 330 0s220 14 330 0 220 14 330 0 220 14 330 0 220 14 330 0 220 14 290 0" stroke="#2878A8" strokeWidth="1.2" strokeOpacity="0.4" fill="none" />
            <path d="M-40 440c110-14 220 14 330 0s220 14 330 0 220 14 330 0 220 14 330 0 220 14 330 0 220 14 290 0v200H-40Z" fill="#105080" fillOpacity="0.28" />
          </g>
          <g className="login-hero-wave login-hero-wave--five animate-wave-drift">
            <path d="M-40 508c140-20 280 20 420 0s280 20 420 0 280 20 420 0 280 20 420 0 280 20 280 0" stroke="#1C6090" strokeWidth="1.4" strokeOpacity="0.42" fill="none" />
            <path d="M-40 508c140-20 280 20 420 0s280 20 420 0 280 20 420 0 280 20 420 0 280 20 280 0v132H-40Z" fill="#083050" fillOpacity="0.4" />
          </g>
          <g className="login-hero-wave login-hero-wave--six animate-wave-drift">
            <path d="M-40 576c180-22 360 22 540 0s360 22 540 0 360 22 540 0 360 22 340 0" stroke="#124870" strokeWidth="1.6" strokeOpacity="0.45" fill="none" />
            <path d="M-40 576c180-22 360 22 540 0s360 22 540 0 360 22 540 0 360 22 340 0v64H-40Z" fill="#061828" fillOpacity="0.55" />
          </g>
          <g className="login-hero-sailboat">
            <animateTransform attributeName="transform" type="translate" from="260 340" to="800 334" dur="52s" repeatCount="indefinite" />
            <g className="animate-boat-float" transform="scale(1.5)">
              <path d="M-62 3q18-3 34 1M-68 7q20-2.5 38 1M-72 12q22-3 40 .5" stroke="white" strokeWidth="1" strokeOpacity="0.3" fill="none" strokeLinecap="round" />
              <ellipse cx="-38" cy="6" rx="22" ry="3.5" fill="white" fillOpacity="0.1" />
              <path d="M-32 3q20 7 64 2l-6 9Q0 18-24 14Z" fill="#152848" />
              <line x1="-26" y1="3.5" x2="28" y2="5" stroke="white" strokeWidth="1.2" strokeOpacity="0.55" />
              <path d="M-20 3.8Q8 2.5 24 5" stroke="white" strokeWidth="0.5" strokeOpacity="0.2" fill="none" />
              <rect x="-2" y="-3" width="16" height="6" rx="1.5" fill="#1E3562" />
              <line x1="6" y1="-75" x2="6" y2="3" stroke="#1A2E55" strokeWidth="1.2" strokeOpacity="0.8" />
              <line x1="-6" y1="-48" x2="18" y2="-48" stroke="#1A2E55" strokeWidth="0.7" strokeOpacity="0.55" />
              <line x1="6" y1="3" x2="36" y2="1" stroke="#1A2E55" strokeWidth="0.8" strokeOpacity="0.65" />
              <path d="M6-73 34 1H6Z" fill="white" fillOpacity="0.88" filter="url(#login-sail-shadow)" />
              <path d="M6-55Q22-28 30 0H6Z" fill="white" fillOpacity="0.08" />
              <line x1="6" y1="-52" x2="24" y2="-6" stroke="#CCE4F0" strokeWidth="0.5" strokeOpacity="0.35" />
              <line x1="6" y1="-28" x2="28" y2="-1" stroke="#CCE4F0" strokeWidth="0.5" strokeOpacity="0.28" />
              <path d="M6-58-22 1H6Z" fill="white" fillOpacity="0.6" filter="url(#login-sail-shadow)" />
              <path d="m6-75 8 4-8 4Z" fill="#4F58FF" fillOpacity="0.85" />
            </g>
          </g>
          <g className="login-hero-sparkles">
            <circle className="login-hero-sparkle" cx="180" cy="344" r="1.8" fill="white" />
            <circle className="login-hero-sparkle" cx="310" cy="356" r="1.2" fill="white" />
            <circle className="login-hero-sparkle" cx="445" cy="348" r="1.5" fill="white" />
            <circle className="login-hero-sparkle" cx="590" cy="362" r="1" fill="white" />
            <circle className="login-hero-sparkle" cx="680" cy="352" r="1.4" fill="white" />
            <circle className="login-hero-sparkle" cx="750" cy="370" r="0.9" fill="white" />
            <circle className="login-hero-sparkle" cx="240" cy="382" r="1" fill="white" />
            <circle className="login-hero-sparkle" cx="520" cy="375" r="1.2" fill="white" />
          </g>
        </svg>
      </section>
    </main>
  );
};
