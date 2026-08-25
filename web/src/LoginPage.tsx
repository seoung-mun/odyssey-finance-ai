import { useEffect, useRef, useState } from "react";

declare global {
  interface Window {
    google?: {
      accounts: { id: {
        initialize(config: { client_id: string; callback: (value: { credential: string }) => void }): void;
        renderButton(element: HTMLElement, options: Record<string, unknown>): void;
      } };
    };
  }
}

export function LoginPage({ clientId, onCredential }: { clientId: string; onCredential: (credential: string) => Promise<void> | void }) {
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
          Promise.resolve(onCredential(credential)).catch(() => setError("로그인하지 못했습니다. 다시 시도해 주세요.")).finally(() => { submitting.current = false; setBusy(false); });
        },
      });
      window.google.accounts.id.renderButton(target.current, { theme: "outline", size: "large", width: 320, text: "continue_with" });
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
      <section className="login-copy">
        <p className="brand-mark">ODYSSEY / 오디세이</p>
        <p className="eyebrow">내 소비에서 출발하는 목표 항로</p>
        <h1>목표까지,<br />흔들려도 길을 잃지 않게.</h1>
        <p className="lead">현재의 소비 흐름을 기준으로 가능한 계획을 비교하고, 현실이 바뀌면 다음 경로를 다시 제안합니다.</p>
      </section>
      <section className="login-panel" aria-labelledby="login-title">
        <div className="route-glyph" aria-hidden="true"><span /><span /><span /></div>
        <h2 id="login-title">항로 시작하기</h2>
        <p>Google 계정으로 안전하게 이어서 관리하세요.</p>
        {!clientId ? <p role="alert" className="notice danger">Google 로그인을 준비하지 못했습니다. 환경 설정을 확인해 주세요.</p> : <div ref={target} className={busy ? "google-button busy" : "google-button"} />}
        {error && <p role="alert" className="notice danger">{error}</p>}
      </section>
    </main>
  );
}
