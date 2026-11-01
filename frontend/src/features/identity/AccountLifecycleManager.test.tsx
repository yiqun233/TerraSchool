import { beforeEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AccountCreateForm, AccountEditForm } from "./AccountLifecycleManager";
const { post, csrf } = vi.hoisted(() => ({ post: vi.fn(), csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({ api: { POST: post }, csrf }));
beforeEach(() => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
});
it("账号创建结果不确定时清空密码并禁止直接重复创建", async () => {
  const user = userEvent.setup();
  post.mockRejectedValue(new Error("network"));
  render(<AccountCreateForm onBusy={vi.fn()} onSaved={vi.fn()} />);
  await user.type(screen.getByLabelText("登录名"), "new-user");
  await user.type(screen.getByLabelText("初始密码"), "long-secret-password");
  await user.click(screen.getByRole("button", { name: "创建账号" }));
  await screen.findByText(/创建结果尚未确认/);
  expect((screen.getByLabelText("初始密码") as HTMLInputElement).value).toBe(
    "",
  );
  expect(
    (screen.getByRole("button", { name: "创建账号" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(post).toHaveBeenCalledTimes(1);
});
it("账号停用固定读取版本，等待CSRF时防重，最后管理员拒绝后锁定", async () => {
  let finish!: (value: { headerName: string; token: string }) => void;
  csrf.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  post.mockResolvedValue({
    response: { ok: false, status: 409 },
    error: { code: "LAST_AUTHORIZATION_ADMINISTRATOR" },
  });
  const props = {
    account: { id: "account", login: "root", active: true, version: 4 },
    onBusy: vi.fn(),
    onSaved: vi.fn(),
  };
  const view = render(<AccountEditForm {...props} />);
  view.rerender(
    <AccountEditForm {...props} account={{ ...props.account, version: 99 }} />,
  );
  const button = screen.getByRole("button", { name: "停用账号" });
  fireEvent.click(button);
  fireEvent.click(button);
  await waitFor(() => expect(csrf).toHaveBeenCalledTimes(1));
  finish({ headerName: "X-CSRF-TOKEN", token: "test" });
  await screen.findByText("不能停用最后一位有效权限管理员。");
  expect(post).toHaveBeenCalledTimes(1);
  expect(post.mock.calls[0][1].body).toEqual({
    expectedVersion: 4,
    active: false,
  });
  expect((button as HTMLButtonElement).disabled).toBe(true);
  expect(props.onSaved).not.toHaveBeenCalled();
});
