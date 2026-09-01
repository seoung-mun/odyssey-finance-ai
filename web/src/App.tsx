import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { ApiError, createApiClient } from "./api";
import { parseAuthTokens } from "./types";
import { DashboardPage } from "./DashboardPage";
import { AppNav } from "./AppNav";
import { ErrorBoundary } from "./ErrorBoundary";
import { LoginPage } from "./LoginPage";
import { OnboardingPage } from "./OnboardingPage";
import { PlansPage } from "./PlansPage";
import { TransactionsPage } from "./TransactionsPage";
import "./styles.css";

const StatusPage = ({ status }: { status: 403 | 404 }) => {
  return (
    <main className="center-page error-page">
      <p className="eyebrow">오류 {status}</p>
      <h1>{status === 403 ? "접근할 수 없는 자원입니다" : "존재하지 않는 화면입니다"}</h1>
      <p>
        {status === 403
          ? "이 계정에 허용된 항로인지 확인해 주세요."
          : "주소가 바뀌었거나 삭제된 화면입니다."}
      </p>
      <Link className="primary link-button" to="/dashboard">
        내 항로로 돌아가기
      </Link>
    </main>
  );
};

const Application = () => {
  const navigate = useNavigate();
  const navigateRef = useRef(navigate);
  navigateRef.current = navigate;
  const token = useRef<string | null>(null);
  const [authenticated, setAuthenticated] = useState(false);
  const [booting, setBooting] = useState(true);
  const [bootError, setBootError] = useState<ApiError | null>(null);
  const bootSequence = useRef(0);
  const bootErrorRef = useRef<HTMLElement>(null);
  const unauthorized = useCallback(() => {
    token.current = null;
    setAuthenticated(false);
    navigateRef.current("/login", { replace: true });
  }, []);
  const api = useMemo(() => createApiClient(() => token.current, unauthorized), [unauthorized]);

  const refresh = useCallback(async () => {
    const sequence = ++bootSequence.current;
    setBooting(true);
    setBootError(null);
    try {
      const result = parseAuthTokens(await api.post("/auth/refresh", undefined, parseAuthTokens));
      if (sequence !== bootSequence.current) return;
      token.current = result.accessToken;
      setAuthenticated(true);
    } catch (reason) {
      if (sequence !== bootSequence.current) return;
      const error = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
      if (error.status !== 401) setBootError(error);
    } finally {
      if (sequence === bootSequence.current) setBooting(false);
    }
  }, [api]);

  useEffect(() => {
    void refresh();
    return () => {
      bootSequence.current += 1;
    };
  }, [refresh]);
  useEffect(() => {
    if (bootError) bootErrorRef.current?.focus();
  }, [bootError]);

  const login = useCallback(
    async (credential: string) => {
      const result = parseAuthTokens(
        await api.post("/auth/google", { idToken: credential }, parseAuthTokens),
      );
      token.current = result.accessToken;
      setAuthenticated(true);
      navigate(result.isNewUser ? "/onboarding" : "/dashboard", { replace: true });
    },
    [api, navigate],
  );

  const logout = useCallback(async () => {
    try {
      await api.post("/auth/logout", undefined, () => undefined);
    } finally {
      bootSequence.current += 1;
      token.current = null;
      setAuthenticated(false);
      navigate("/login", { replace: true });
    }
  }, [api, navigate]);

  if (booting)
    return (
      <main className="center-page" aria-busy="true">
        <p className="brand-mark">ODYSSEY</p>
        <h1>항로를 이어 불러옵니다</h1>
      </main>
    );
  if (bootError)
    return (
      <main ref={bootErrorRef} tabIndex={-1} role="alert" className="center-page error-page">
        <p className="eyebrow">세션 확인 오류</p>
        <h1>세션을 확인하지 못했습니다</h1>
        {bootError.requestId && (
          <p>
            요청 ID: <code>{bootError.requestId}</code>
          </p>
        )}
        <p>로그아웃된 것은 아닙니다. 연결을 확인하고 다시 시도해 주세요.</p>
        <button className="primary" onClick={() => void refresh()}>
          세션 다시 확인하기
        </button>
      </main>
    );
  const protect = (page: ReactNode) =>
    authenticated ? (
      <>
        <AppNav onLogout={logout} />
        {page}
      </>
    ) : (
      <Navigate to="/login" replace />
    );
  return (
    <Routes>
      <Route
        path="/login"
        element={
          authenticated ? (
            <Navigate to="/dashboard" replace />
          ) : (
            <LoginPage
              clientId={import.meta.env.VITE_GOOGLE_CLIENT_ID ?? ""}
              onCredential={login}
            />
          )
        }
      />
      <Route path="/onboarding" element={protect(<OnboardingPage api={api} />)} />
      <Route path="/dashboard" element={protect(<DashboardPage api={api} />)} />
      <Route path="/transactions" element={protect(<TransactionsPage api={api} />)} />
      <Route path="/plans" element={protect(<PlansPage api={api} />)} />
      <Route path="/forbidden" element={<StatusPage status={403} />} />
      <Route path="/not-found" element={<StatusPage status={404} />} />
      <Route path="/" element={<Navigate to={authenticated ? "/dashboard" : "/login"} replace />} />
      <Route path="*" element={<StatusPage status={404} />} />
    </Routes>
  );
};

const App = () => {
  return (
    <ErrorBoundary>
      <BrowserRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
        <Application />
      </BrowserRouter>
    </ErrorBoundary>
  );
};

export default App;
