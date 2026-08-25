import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorBoundary } from "./ErrorBoundary";

const Broken = (): never => {
  throw new Error("render broke");
};

it("offers a dedicated recovery screen for rendering failures", async () => {
  const reload = vi.fn();
  vi.spyOn(console, "error").mockImplementation(() => undefined);
  render(
    <ErrorBoundary onReload={reload}>
      <Broken />
    </ErrorBoundary>,
  );
  expect(screen.getByRole("heading", { name: "화면을 표시하지 못했습니다" })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "화면 다시 열기" }));
  expect(reload).toHaveBeenCalledOnce();
});
