import { afterEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChangePasswordGate } from "./ChangePasswordGate";

const { changePassword, SessionExpired } = vi.hoisted(() => ({
  changePassword: vi.fn(),
  SessionExpired: class SessionExpired extends Error {},
}));
vi.mock("../../shared/http", () => ({ changePassword, SessionExpired }));

afterEach(() => vi.clearAllMocks());

function setup() {
  const onChanged = vi.fn();
  const onSessionLost = vi.fn();
  render(
    <ChangePasswordGate onChanged={onChanged} onSessionLost={onSessionLost} />,
  );
  return { onChanged, onSessionLost };
}

async function fill(
  user: ReturnType<typeof userEvent.setup>,
  current: string,
  next: string,
  confirm = next,
) {
  await user.type(await screen.findByLabelText("当前密码"), current);
  await user.type(screen.getByLabelText("新密码"), next);
  await user.type(screen.getByLabelText("确认新密码"), confirm);
}

it("说明了为什么必须改密以及密码要求", async () => {
  setup();
  expect(screen.getByText("请先设置新密码")).toBeTruthy();
  expect(screen.getByText(/至少 12 位/)).toBeTruthy();
});

it("两次新密码不一致时就地拦下，不调用接口", async () => {
  const user = userEvent.setup();
  setup();
  await fill(user, "Old-Password-1", "New-Password-123", "New-Password-999");
  await user.click(screen.getByRole("button", { name: "保存新密码" }));
  expect(await screen.findByText("两次输入的新密码不一致")).toBeTruthy();
  expect(changePassword).not.toHaveBeenCalled();
});

it("提交成功后交给调用方把用户送回登录页", async () => {
  const user = userEvent.setup();
  changePassword.mockResolvedValue(undefined);
  const { onChanged } = setup();
  await fill(user, "Old-Password-1", "New-Password-123");
  await user.click(screen.getByRole("button", { name: "保存新密码" }));
  await waitFor(() =>
    expect(changePassword).toHaveBeenCalledWith(
      "Old-Password-1",
      "New-Password-123",
    ),
  );
  expect(onChanged).toHaveBeenCalled();
});

it("服务端拒绝时原样展示原因，不清空输入", async () => {
  const user = userEvent.setup();
  changePassword.mockRejectedValue(new Error("新密码不能与登录名相同"));
  const { onChanged } = setup();
  await fill(user, "Old-Password-1", "New-Password-123");
  await user.click(screen.getByRole("button", { name: "保存新密码" }));
  expect(await screen.findByText("新密码不能与登录名相同")).toBeTruthy();
  expect((screen.getByLabelText("新密码") as HTMLInputElement).value).toBe(
    "New-Password-123",
  );
  expect(onChanged).not.toHaveBeenCalled();
});

it("会话已失效时交给调用方重新登录", async () => {
  const user = userEvent.setup();
  changePassword.mockRejectedValue(new SessionExpired("登录已失效"));
  const { onSessionLost } = setup();
  await fill(user, "Old-Password-1", "New-Password-123");
  await user.click(screen.getByRole("button", { name: "保存新密码" }));
  await waitFor(() => expect(onSessionLost).toHaveBeenCalled());
});
