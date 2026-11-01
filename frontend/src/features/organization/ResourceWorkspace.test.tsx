import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ResourceForm } from "./ResourceWorkspace";
const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
it("资源编辑固定读取版本，冲突时保留草稿并禁止覆盖", async () => {
  get.mockResolvedValue({ response: { ok: true }, data: [] });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({
    response: { ok: false, status: 409 },
    error: { code: "VERSION_CONFLICT" },
  });
  const props = {
    kind: "ROOM" as const,
    selected: {
      id: "room",
      schoolId: "school",
      schoolName: "学校甲",
      kind: "ROOM" as const,
      name: "教室甲",
      capacity: 30,
      active: true,
      version: 4,
    },
    onBusy: vi.fn(),
    onLocked: vi.fn(),
    onSaved: vi.fn(),
    onCancel: vi.fn(),
  };
  const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    }),
    user = userEvent.setup();
  const view = render(
    <QueryClientProvider client={client}>
      <ResourceForm {...props} />
    </QueryClientProvider>,
  );
  view.rerender(
    <QueryClientProvider client={client}>
      <ResourceForm {...props} selected={{ ...props.selected, version: 99 }} />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await screen.findByText("资料或请求身份已变化，请关闭后刷新核对。");
  expect(post.mock.calls[0][1].body.expectedVersion).toBe(4);
  expect((screen.getByLabelText("教室名称") as HTMLInputElement).value).toBe(
    "教室甲",
  );
  expect(
    (screen.getByRole("button", { name: /保\s*存/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});

it("创建资源在令牌获取失败后重新编辑，提交修改后的草稿", async () => {
  vi.clearAllMocks();
  get.mockImplementation(
    async (
      _path: string,
      options: { params: { query: { type: string } } },
    ) => ({
      response: { ok: true },
      data:
        options.params.query.type === "SCHOOL"
          ? [{ id: "school-a", name: "学校甲" }]
          : [],
    }),
  );
  csrf
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValueOnce({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 201 } });
  const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    }),
    user = userEvent.setup();
  render(
    <QueryClientProvider client={client}>
      <ResourceForm
        kind="EMPLOYEE"
        onBusy={vi.fn()}
        onLocked={vi.fn()}
        onSaved={vi.fn()}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  );
  await user.click(screen.getByLabelText("学校"));
  await user.click(await screen.findByText("学校甲"));
  const name = screen.getByLabelText("员工名称");
  await user.type(name, "旧名称");
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await screen.findByText("无法获取安全令牌，请重试。");
  await user.clear(name);
  await user.type(name, "新名称");
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body.name).toBe("新名称");
});

it("教师关联员工支持搜索首批20项以外的员工", async () => {
  vi.clearAllMocks();
  get.mockImplementation(
    async (
      _path: string,
      options: { params: { query: { type: string; search: string } } },
    ) => ({
      response: { ok: true },
      data:
        options.params.query.type === "EMPLOYEE"
          ? options.params.query.search.includes("远端员工")
            ? [{ id: "employee-21", name: "远端员工" }]
            : Array.from({ length: 20 }, (_, i) => ({
                id: `employee-${i}`,
                name: `员工${i}`,
              }))
          : [],
    }),
  );
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 } });
  const user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ResourceForm
        kind="TEACHER"
        selected={{
          id: "teacher",
          schoolId: "school-a",
          schoolName: "学校甲",
          kind: "TEACHER",
          name: "教师",
          active: true,
          version: 0,
        }}
        onBusy={vi.fn()}
        onLocked={vi.fn()}
        onSaved={vi.fn()}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  );
  await user.type(screen.getByLabelText("关联员工（可选）"), "远端员工");
  await user.click(await screen.findByText("远端员工"));
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body.employeeId).toBe("employee-21");
});
