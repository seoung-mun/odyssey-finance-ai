import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ApiError, type ApiClient } from "./api";
import { OnboardingPage } from "./OnboardingPage";

it("keeps entered values when the network fails", async () => {
  const api = { put: vi.fn().mockRejectedValue(new ApiError(0, "NETWORK_ERROR")), post: vi.fn() } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><OnboardingPage api={api} /></MemoryRouter>);
  await userEvent.click(screen.getByRole("button", { name: "직접 시작하기" }));
  await userEvent.type(screen.getByLabelText("월 소득"), "3600000");
  await userEvent.type(screen.getByLabelText("월 고정비"), "1100000");
  await userEvent.type(screen.getByLabelText("목표 이름"), "작업실");
  await userEvent.type(screen.getByLabelText("목표 금액"), "30000000");
  await userEvent.type(screen.getByLabelText("목표 날짜"), "2028-12-31");
  await userEvent.click(screen.getByRole("button", { name: "계획 만들기" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("입력값은 그대로 보관했습니다");
  expect(screen.getByLabelText("목표 이름")).toHaveValue("작업실");
});

it("loads sample data only after an explicit choice", async () => {
  const api = { post: vi.fn().mockResolvedValue({ loaded: true, sampleDataLoadedAt: "2026-08-25T00:00:00+09:00" }) } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><OnboardingPage api={api} /></MemoryRouter>);
  expect(api.post).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: "샘플로 둘러보기" }));
  expect(api.post).toHaveBeenCalledWith("/me/sample-data");
});

it("blocks a rapid duplicate sample request", () => {
  const api = { post: vi.fn().mockReturnValue(new Promise(() => undefined)) } as unknown as ApiClient;
  render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><OnboardingPage api={api} /></MemoryRouter>);
  const button = screen.getByRole("button", { name: "샘플로 둘러보기" });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(api.post).toHaveBeenCalledOnce();
});
