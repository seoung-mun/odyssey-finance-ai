import { Component, type ErrorInfo, type ReactNode } from "react";

export class ErrorBoundary extends Component<
  { children: ReactNode; onReload?: () => void },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("렌더링 오류", error.name, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <main className="center-page error-page">
        <p className="eyebrow">화면 오류</p>
        <h1>화면을 표시하지 못했습니다</h1>
        <p>입력 중인 내용은 그대로 두고 이 화면만 다시 열어 보세요.</p>
        <button className="primary" onClick={this.props.onReload ?? (() => window.location.reload())}>
          화면 다시 열기
        </button>
      </main>
    );
  }
}
