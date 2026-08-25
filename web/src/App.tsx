import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { createApiClient } from "./api";
import type { AuthTokens } from "./types";
import { DashboardPage } from "./DashboardPage";
import { ErrorBoundary } from "./ErrorBoundary";
import { LoginPage } from "./LoginPage";
import { OnboardingPage } from "./OnboardingPage";
import "./styles.css";

function StatusPage({ status }: { status: 403 | 404 }) {
  return <main className="center-page error-page"><p className="eyebrow">오류 {status}</p><h1>{status === 403 ? "접근할 수 없는 자원입니다" : "존재하지 않는 화면입니다"}</h1><p>{status === 403 ? "이 계정에 허용된 항로인지 확인해 주세요." : "주소가 바뀌었거나 삭제된 화면입니다."}</p><Link className="primary link-button" to="/dashboard">내 항로로 돌아가기</Link></main>;
}

function Application() {
  const navigate = useNavigate();
  const token = useRef<string | null>(null);
  const [authenticated, setAuthenticated] = useState(false);
  const [booting, setBooting] = useState(true);
  const unauthorized = useCallback(() => {
    token.current = null;
    setAuthenticated(false);
    navigate("/login", { replace: true });
  }, [navigate]);
  const api = useMemo(() => createApiClient(() => token.current, unauthorized), [unauthorized]);

  useEffect(() => {
    let active = true;
    api.post<AuthTokens>("/auth/refresh").then((result) => {
      if (!active) return;
      token.current = result.accessToken;
      setAuthenticated(true);
    }).catch(() => undefined).finally(() => { if (active) setBooting(false); });
    return () => { active = false; };
  }, [api]);

  const login = useCallback(async (credential: string) => {
    const result = await api.post<AuthTokens>("/auth/google", { idToken: credential });
    token.current = result.accessToken;
    setAuthenticated(true);
    navigate(result.isNewUser ? "/onboarding" : "/dashboard", { replace: true });
  }, [api, navigate]);

  if (booting) return <main className="center-page" aria-busy="true"><p className="brand-mark">ODYSSEY</p><h1>항로를 이어 불러옵니다</h1></main>;
  const protect = (page: ReactNode) => authenticated ? page : <Navigate to="/login" replace />;
  return <Routes>
    <Route path="/login" element={authenticated ? <Navigate to="/dashboard" replace /> : <LoginPage clientId={import.meta.env.VITE_GOOGLE_CLIENT_ID ?? ""} onCredential={login} />} />
    <Route path="/onboarding" element={protect(<OnboardingPage api={api} />)} />
    <Route path="/dashboard" element={protect(<DashboardPage api={api} />)} />
    <Route path="/forbidden" element={<StatusPage status={403} />} />
    <Route path="/not-found" element={<StatusPage status={404} />} />
    <Route path="/" element={<Navigate to={authenticated ? "/dashboard" : "/login"} replace />} />
    <Route path="*" element={<StatusPage status={404} />} />
  </Routes>;
}

export default function App() {
  return <ErrorBoundary><BrowserRouter><Application /></BrowserRouter></ErrorBoundary>;
}
