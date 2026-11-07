import { afterEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OnboardingGuide } from "./OnboardingGuide";

const { onSchoolClosed } = vi.hoisted(() => ({ onSchoolClosed: vi.fn() }));
vi.mock("./SchoolCreator", () => ({
  SchoolCreator: ({ onClosed }: { onClosed: () => void }) => (
    <button
      type="button"
      onClick={() => {
        onSchoolClosed();
        onClosed();
      }}
    >
      开通学校
    </button>
  ),
}));

afterEach(() => vi.clearAllMocks());

it("说明这是一切业务的起点，并列出三步", () => {
  render(<OnboardingGuide onSchoolCreated={vi.fn()} />);
  expect(screen.getByText("先建一所学校")).toBeTruthy();
  expect(screen.getByText(/学校是起点/)).toBeTruthy();
  expect(screen.getByText("建学校")).toBeTruthy();
  expect(screen.getByText("建校区与部门")).toBeTruthy();
  expect(screen.getByText("添加员工并分配角色")).toBeTruthy();
});

it("第一步可直接开通学校，其余步骤说明要等内容齐备", () => {
  render(<OnboardingGuide onSchoolCreated={vi.fn()} />);
  expect(screen.getByRole("button", { name: "开通学校" })).toBeTruthy();
  expect(screen.getByText(/第 2、3 步要等学校建好后才能进行/)).toBeTruthy();
  // 后两步不提供按钮，避免给出点不通的入口。
  expect(screen.queryByRole("button", { name: /建校区/ })).toBeNull();
});

it("学校创建完成后通知调用方刷新会话", async () => {
  const user = userEvent.setup();
  const onSchoolCreated = vi.fn();
  render(<OnboardingGuide onSchoolCreated={onSchoolCreated} />);
  await user.click(screen.getByRole("button", { name: "开通学校" }));
  expect(onSchoolCreated).toHaveBeenCalled();
});
