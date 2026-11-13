import { beforeEach, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RoleTemplateForm, StartingRoleCreator } from "./RoleTemplateManager";
const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
/** 表单只接受服务端目录生成的分组选项，不在组件里维护动作清单。 */
const actions = [
  {
    label: "组织与身份",
    options: [{ value: "ORGANIZATION_READ", label: "组织查看" }],
  },
];
beforeEach(() => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
});

/** 预设模板读取走 TanStack Query，测试需要独立 QueryClient。 */
function renderWithQuery(ui: ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
}
it("创建响应不确定时锁定草稿，重试沿用原请求身份和参数", async () => {
  const user = userEvent.setup();
  const saved = vi.fn();
  post
    .mockResolvedValueOnce({
      response: { ok: false, status: 503, headers: new Headers() },
    })
    .mockResolvedValueOnce({ response: { ok: true, status: 201 } });
  render(
    <RoleTemplateForm actions={actions} onBusy={vi.fn()} onSaved={saved} />,
  );
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
    actions,
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

const startingTemplates = [
  {
    code: "START_FINANCE",
    name: "财务",
    purpose: "收款、分配、退款、锁月与对账",
    actionCount: 2,
    actions: ["ORGANIZATION_READ", "FUNDS_READ"],
  },
];
const permissionCatalog = [
  { code: "ORGANIZATION_READ", label: "组织查看", group: "组织与身份" },
  { code: "FUNDS_READ", label: "收款查看", group: "报名与收款" },
];
const generatedRole = {
  id: "role-1",
  code: "START_FINANCE",
  name: "财务",
  version: 0,
  active: true,
  actions: [],
  assignedAccounts: 0,
};

it("从模板生成时带出名称与代码，提交模板和稳定请求身份", async () => {
  const user = userEvent.setup();
  const generated = vi.fn();
  get.mockResolvedValue({
    response: { ok: true, status: 200 },
    data: startingTemplates,
  });
  post.mockResolvedValue({
    response: { ok: true, status: 201, headers: new Headers() },
    data: generatedRole,
  });
  renderWithQuery(
    <StartingRoleCreator
      catalog={permissionCatalog}
      onBusy={vi.fn()}
      onGenerated={generated}
      onCancel={vi.fn()}
    />,
  );
  await user.click(await screen.findByText("财务（START_FINANCE）· 2 个动作"));
  expect((screen.getByLabelText("角色名称") as HTMLInputElement).value).toBe(
    "财务",
  );
  expect((screen.getByLabelText("角色代码") as HTMLInputElement).value).toBe(
    "START_FINANCE",
  );
  expect(screen.getByText(/组织查看/)).toBeTruthy();
  expect(screen.getByText(/收款查看/)).toBeTruthy();
  await user.click(screen.getByRole("button", { name: "按模板生成角色" }));
  await waitFor(() => expect(generated).toHaveBeenCalledTimes(1));
  const body = post.mock.calls[0][1].body;
  expect(body).toMatchObject({
    templateCode: "START_FINANCE",
    name: "财务",
    code: "START_FINANCE",
  });
  expect(body.requestId).toMatch(/^[0-9a-f-]{36}$/i);
});

it("代码冲突时就地提示，不覆盖已有角色", async () => {
  const user = userEvent.setup();
  const generated = vi.fn();
  get.mockResolvedValue({
    response: { ok: true, status: 200 },
    data: startingTemplates,
  });
  post.mockResolvedValue({
    response: { ok: false, status: 409, headers: new Headers() },
    error: { code: "ROLE_CODE_EXISTS" },
  });
  renderWithQuery(
    <StartingRoleCreator
      catalog={permissionCatalog}
      onBusy={vi.fn()}
      onGenerated={generated}
      onCancel={vi.fn()}
    />,
  );
  await user.click(await screen.findByText("财务（START_FINANCE）· 2 个动作"));
  await user.click(screen.getByRole("button", { name: "按模板生成角色" }));
  expect(await screen.findByText(/角色代码已存在/)).toBeTruthy();
  expect(generated).not.toHaveBeenCalled();
});

it("生成结果不确定时锁定参数，重试沿用同一请求身份", async () => {
  const user = userEvent.setup();
  const generated = vi.fn();
  get.mockResolvedValue({
    response: { ok: true, status: 200 },
    data: startingTemplates,
  });
  post
    .mockResolvedValueOnce({
      response: { ok: false, status: 503, headers: new Headers() },
    })
    .mockResolvedValueOnce({
      response: { ok: true, status: 201, headers: new Headers() },
      data: generatedRole,
    });
  renderWithQuery(
    <StartingRoleCreator
      catalog={permissionCatalog}
      onBusy={vi.fn()}
      onGenerated={generated}
      onCancel={vi.fn()}
    />,
  );
  await user.click(await screen.findByText("财务（START_FINANCE）· 2 个动作"));
  await user.click(screen.getByRole("button", { name: "按模板生成角色" }));
  await screen.findByText(/生成结果尚未确认/);
  expect((screen.getByLabelText("角色名称") as HTMLInputElement).disabled).toBe(
    true,
  );
  await user.click(screen.getByRole("button", { name: "重试相同生成" }));
  await waitFor(() => expect(generated).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body.requestId).toBe(
    post.mock.calls[1][1].body.requestId,
  );
});
