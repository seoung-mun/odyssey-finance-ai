import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";

const auth = { accessToken: "fixture-token", expiresIn: 900, isNewUser: false };
const emptyDashboard = {
  goal: null,
  activePlan: null,
  selectedOption: null,
  pendingProposal: null,
  monthProgress: null,
};

it("announces and focuses a refresh network failure without treating it as logged out", async () => {
  const fetcher = vi.fn().mockRejectedValue(new Error("offline"));
  vi.stubGlobal("fetch", fetcher);
  try {
    render(<App />);
    expect(
      await screen.findByRole("heading", { name: "세션을 확인하지 못했습니다" }),
    ).toBeInTheDocument();
    const alert = screen.getByRole("alert");
    expect(alert).toHaveFocus();
    expect(screen.queryByRole("heading", { name: "항로 시작하기" })).not.toBeInTheDocument();
  } finally {
    vi.unstubAllGlobals();
  }
});

it("shows a refresh request ID and retries without treating the user as logged out", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 500, code: "INTERNAL", requestId: "refresh-500" }), {
        status: 500,
      }),
    )
    .mockResolvedValueOnce(new Response(JSON.stringify(auth), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyDashboard), { status: 200 }));
  vi.stubGlobal("fetch", fetcher);
  try {
    render(<App />);
    expect(await screen.findByText(/refresh-500/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "세션 다시 확인하기" }));
    await waitFor(() =>
      expect(fetcher.mock.calls.map(([input]) => input)).toEqual([
        "/api/v1/auth/refresh",
        "/api/v1/auth/refresh",
        "/api/v1/dashboard",
      ]),
    );
    expect(
      await screen.findByRole("heading", { name: "아직 목표가 없습니다" }),
    ).toBeInTheDocument();
  } finally {
    vi.unstubAllGlobals();
  }
});

it("logs out through the product API and returns to login", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(auth), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyDashboard), { status: 200 }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetcher);
  try {
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));
    expect(
      await screen.findByRole("heading", { name: "Google로 계획 시작하기" }),
    ).toBeInTheDocument();
    expect(fetcher.mock.calls[2][0]).toBe("/api/v1/auth/logout");
  } finally {
    vi.unstubAllGlobals();
  }
});
