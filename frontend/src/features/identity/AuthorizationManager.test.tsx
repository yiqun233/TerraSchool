import { it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthorizationForm } from "./AuthorizationManager";
import type { components } from "../../shared/generated/api";
const { post, csrf } = vi.hoisted(() => ({ post: vi.fn(), csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({ api: { POST: post }, csrf }));
const account: components["schemas"]["AccountAuthorization"] = {
  accountId: "account-a",
  login: "管理测试账号",
  active: true,
  version: 4,
  rules: [{ action: "ORGANIZATION_READ", effect: "ALLOW" }],
  assignments: [],
};
beforeEach(() => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test-token" });
});
function editor(saved = vi.fn()) {
  const props = {
    account,
    schools: [],
    roles: [],
    onSchoolSearch: vi.fn(),
    onRoleSearch: vi.fn(),
    onBusy: vi.fn(),
    onSaved: saved,
  };
  return { props, ...render(<AuthorizationForm {...props} />) };
}
it("CSRF等待期间拦截重复提交，使用打开时的版本并在冲突后阻止覆盖", async () => {
  let finish!: (value: { headerName: string; token: string }) => void;
  csrf.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  post.mockResolvedValue({
    response: {
      ok: false,
      status: 409,
      headers: new Headers({
        "X-Trace-Id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      }),
    },
    error: { code: "AUTHORIZATION_VERSION_CONFLICT" },
  });
  const { props, rerender } = editor();
  rerender(
    <AuthorizationForm {...props} account={{ ...account, version: 99 }} />,
  );
  const form = screen
    .getByRole("button", { name: "保存整套授权" })
    .closest("form")!;
  fireEvent.submit(form);
  fireEvent.submit(form);
  await waitFor(() => expect(csrf).toHaveBeenCalledTimes(1));
  expect(post).not.toHaveBeenCalled();
  finish({ headerName: "X-CSRF-TOKEN", token: "test-token" });
  await screen.findByText(/授权已变化，请关闭后重新读取/);
  expect(post).toHaveBeenCalledTimes(1);
  expect(post.mock.calls[0][1].body).toEqual({
    expectedVersion: 4,
    rules: [
      { action: "ORGANIZATION_READ", effect: "ALLOW", schoolId: undefined },
    ],
    assignments: [],
  });
  expect(
    (screen.getByRole("button", { name: "保存整套授权" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(props.onSaved).not.toHaveBeenCalled();
});
it("最后管理员拒绝有明确提示，网络结果不确定时不自动重试", async () => {
  const user = userEvent.setup();
  post.mockResolvedValueOnce({
    response: { ok: false, status: 409, headers: new Headers() },
    error: { code: "LAST_AUTHORIZATION_ADMINISTRATOR" },
  });
  const first = editor();
  await user.click(screen.getByRole("button", { name: "保存整套授权" }));
  await screen.findByText("不能移除最后一位有效权限管理员");
  first.unmount();
  post.mockRejectedValueOnce(new Error("connection lost"));
  const second = editor();
  await user.click(screen.getByRole("button", { name: "保存整套授权" }));
  await screen.findByText(
    /保存结果尚未确认，请关闭后重新读取核对，勿直接重复提交/,
  );
  expect(
    (screen.getByRole("button", { name: "保存整套授权" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(second.props.onSaved).not.toHaveBeenCalled();
  expect(post).toHaveBeenCalledTimes(2);
});

it("角色选项刷新不会静默替换分配草稿中的模板版本", async () => {
  const user = userEvent.setup();
  post.mockResolvedValue({
    response: { ok: false, status: 409, headers: new Headers() },
    error: { code: "ROLE_VERSION_CONFLICT" },
  });
  const props = {
    account: {
      ...account,
      assignments: [
        { roleId: "role-a", schoolId: "school-a", expectedRoleVersion: 7 },
      ],
    },
    schools: [{ value: "school-a", label: "学校甲" }],
    roles: [{ value: "role-a", label: "只读角色", version: 7 }],
    onSchoolSearch: vi.fn(),
    onRoleSearch: vi.fn(),
    onBusy: vi.fn(),
    onSaved: vi.fn(),
  };
  const view = render(<AuthorizationForm {...props} />);
  view.rerender(
    <AuthorizationForm
      {...props}
      roles={[{ value: "role-a", label: "已更新角色", version: 9 }]}
    />,
  );
  await user.click(screen.getByRole("button", { name: "保存整套授权" }));
  await screen.findByText(/授权已变化/);
  expect(post.mock.calls[0][1].body.assignments[0].expectedRoleVersion).toBe(7);
});
