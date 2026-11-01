import { beforeEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RoleTemplateForm } from "./RoleTemplateManager";
const { post, csrf } = vi.hoisted(() => ({ post: vi.fn(), csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({ api: { POST: post }, csrf }));
beforeEach(() => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
});
it("创建响应不确定时锁定草稿，重试沿用原请求身份和参数", async () => {
  const user = userEvent.setup();
  const saved = vi.fn();
  post
    .mockResolvedValueOnce({
      response: { ok: false, status: 503, headers: new Headers() },
    })
    .mockResolvedValueOnce({ response: { ok: true, status: 201 } });
  render(<RoleTemplateForm onBusy={vi.fn()} onSaved={saved} />);
  await user.type(screen.getByLabelText("角色代码"), "TEST_ROLE");
  await user.type(screen.getByLabelText("角色名称"), "测试模板");
  await user.click(screen.getByRole("button", { name: "创建角色模板" }));
  await screen.findByText(/创建结果尚未确认/);
  expect((screen.getByLabelText("角色代码") as HTMLInputElement).disabled).toBe(
    true,
  );
  await user.click(screen.getByRole("button", { name: "重试相同创建" }));
  await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toEqual(post.mock.calls[1][1].body);
  expect(post.mock.calls[0][1].body.requestId).toMatch(/^[0-9a-f-]{36}$/i);
});
it("编辑固定原版本，CSRF等待时防重，影响范围冲突后不覆盖", async () => {
  let finish!: (value: { headerName: string; token: string }) => void;
  csrf.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  post.mockResolvedValue({
    response: { ok: false, status: 409, headers: new Headers() },
    error: { code: "ROLE_VERSION_CONFLICT" },
  });
  const props = {
    selected: {
      id: "role",
      code: "legacy-code",
      name: "原模板",
      active: true,
      version: 4,
      assignedAccounts: 2,
      actions: [],
    },
    onBusy: vi.fn(),
    onSaved: vi.fn(),
  };
  const view = render(<RoleTemplateForm {...props} />);
  view.rerender(
    <RoleTemplateForm
      {...props}
      selected={{ ...props.selected, version: 99 }}
    />,
  );
  const form = screen
    .getByRole("button", { name: "保存角色模板" })
    .closest("form")!;
  fireEvent.submit(form);
  fireEvent.submit(form);
  await waitFor(() => expect(csrf).toHaveBeenCalledTimes(1));
  finish({ headerName: "X-CSRF-TOKEN", token: "test" });
  await screen.findByText("角色定义或分配范围已变化，请关闭后重新读取");
  expect(post).toHaveBeenCalledTimes(1);
  expect(post.mock.calls[0][1].body.expectedVersion).toBe(4);
  expect(
    (screen.getByRole("button", { name: "保存角色模板" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
