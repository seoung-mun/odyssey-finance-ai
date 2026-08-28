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
          <p className="brand-mark">ODYSSEY / 오디세이</p>
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
          <p className="login-privacy">금융정보는 계획 계산에만 사용해요.</p>
        </div>
      </section>
      <section className="login-hero" aria-hidden="true">
        <div className="login-hero-sky" />
        <div className="login-hero-horizon" />
        <svg className="login-hero-boat" viewBox="0 0 80 80" focusable="false">
          <path d="M12 54h56l-9 11H21z" />
          <path d="M39 18v36l-23-9zM43 25l19 22-19 7z" />
        </svg>
      </section>
    </main>
  );
};
