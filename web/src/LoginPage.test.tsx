import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { LoginPage } from "./LoginPage";

it("explains configuration when the Google client ID is missing", () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <LoginPage clientId="" onCredential={vi.fn()} />
    </MemoryRouter>,
  );
  expect(screen.getByRole("alert")).toHaveTextContent("Google 로그인을 준비하지 못했습니다");
  expect(screen.queryByText(/데모/)).not.toBeInTheDocument();
});

it("introduces the financial planner with an ornamental voyage hero", () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <LoginPage clientId="" onCredential={vi.fn()} />
    </MemoryRouter>,
  );

  expect(screen.getByRole("heading", { level: 1, name: "오늘의 돈에서 원하는 미래까지." })).toBeInTheDocument();
  expect(document.querySelector(".login-hero[aria-hidden='true']")).toBeInTheDocument();
});

it("keeps the Odyssey logo and complete daytime voyage signatures", () => {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <LoginPage clientId="" onCredential={vi.fn()} />
    </MemoryRouter>,
  );

  expect(document.querySelector(".odyssey-logo-mark")).toBeInTheDocument();
  expect(document.querySelector(".login-hero-clouds")).toBeInTheDocument();
  expect(document.querySelector(".login-hero-island .login-hero-lighthouse")).toBeInTheDocument();
  expect(document.querySelectorAll(".login-hero-wave")).toHaveLength(6);
  expect(document.querySelector(".login-hero-sailboat animateTransform")).toBeInTheDocument();
});
