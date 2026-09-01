import { NavLink } from "react-router-dom";

export const AppNav = ({ onLogout }: { onLogout: () => Promise<void> }) => (
  <header className="topbar app-nav" aria-label="Odyssey 앱 헤더">
    <NavLink className="odyssey-brand" to="/dashboard" aria-label="Odyssey 홈">
      <span className="odyssey-logo-mark" aria-hidden="true">◈</span>
      <span>Odyssey</span>
    </NavLink>
    <nav aria-label="주요 메뉴">
      <NavLink to="/dashboard">항로</NavLink>
      <NavLink to="/transactions">거래</NavLink>
      <NavLink to="/plans">계획</NavLink>
    </nav>
    <button className="text-button" onClick={() => void onLogout()}>로그아웃</button>
  </header>
);
