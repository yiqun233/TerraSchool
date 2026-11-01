import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { OrganizationEditor } from "./OrganizationEditor";

const { post, csrf } = vi.hoisted(() => ({ post: vi.fn(), csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({
  api: {
    GET: vi.fn(async (path: string) => ({
      response: { ok: true },
      data: path.endsWith("school-options")
        ? [{ id: "school-a", name: "学校甲" }]
        : { editableSchoolIds: ["school-a"] },
    })),
    POST: post,
  },
  csrf,
}));
beforeEach(() => {
  post.mockReset();
  csrf.mockReset();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "token" });
});
function wrapper(children: React.ReactNode) {
  return (
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      {children}
    </QueryClientProvider>
  );
}

describe("组织维护提交保护", () => {
  it("502后禁用关闭与编辑，重试沿用相同请求身份", async () => {
    const user = userEvent.setup();
    const saved = vi.fn();
    post
      .mockResolvedValueOnce({
        response: {
          ok: false,
          status: 502,
          headers: new Headers({
            "X-Trace-Id": "11111111-1111-4111-8111-111111111111",
          }),
        },
      })
      .mockResolvedValueOnce({ response: { ok: true, status: 201 } });
    render(wrapper(<OrganizationEditor identityKey="a" onSaved={saved} />));
    await user.click(
      await screen.findByRole("button", { name: "新增校区 / 部门" }),
    );
    await user.click(screen.getByRole("combobox", { name: "所属学校" }));
    await user.click(await screen.findByText("学校甲"));
    await user.type(screen.getByRole("textbox", { name: "名称" }), "测试部门");
    await user.click(screen.getByRole("button", { name: /保\s*存/ }));
    await screen.findByText(/提交结果尚未确认/);
    await screen.findByText(/追踪编号：11111111-1111-4111-8111-111111111111/);
    expect(
      (screen.getByRole("textbox", { name: "名称" }) as HTMLInputElement)
        .disabled,
    ).toBe(true);
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: /保\s*存/ }));
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(post.mock.calls[0][1].body).toEqual(post.mock.calls[1][1].body);
  }, 10000);
  it("列表刷新后仍提交打开编辑时的版本", async () => {
    const user = userEvent.setup();
    const saved = vi.fn();
    post.mockResolvedValue({
      response: {
        ok: false,
        status: 409,
        headers: new Headers({
          "X-Trace-Id": "22222222-2222-4222-8222-222222222222",
        }),
      },
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const initial = {
      id: "unit-a",
      schoolId: "school-a",
      kind: "CAMPUS",
      name: "旧名称",
      version: 0,
    };
    const view = (version: number) => (
      <QueryClientProvider client={client}>
        <OrganizationEditor
          selected={{ ...initial, version }}
          identityKey="a"
          onSaved={saved}
        />
      </QueryClientProvider>
    );
    const result = render(view(0));
    await user.click(await screen.findByRole("button", { name: /改\s*名/ }));
    result.rerender(view(1));
    await user.clear(screen.getByRole("textbox", { name: "名称" }));
    await user.type(screen.getByRole("textbox", { name: "名称" }), "编辑草稿");
    await user.click(screen.getByRole("button", { name: /保\s*存/ }));
    await screen.findByText(/版本已变更/);
    await screen.findByText(/追踪编号：22222222-2222-4222-8222-222222222222/);
    expect(post.mock.calls[0][1].body.expectedVersion).toBe(0);
    expect(saved).not.toHaveBeenCalled();
  });
  it("CSRF尚未返回时连续提交也只发起一次修改", { timeout: 15000 }, async () => {
    const user = userEvent.setup();
    const saved = vi.fn();
    let release!: (value: { headerName: string; token: string }) => void;
    csrf.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    post.mockResolvedValue({ response: { ok: true, status: 201 } });
    render(wrapper(<OrganizationEditor identityKey="a" onSaved={saved} />));
    await user.click(
      await screen.findByRole("button", { name: "新增校区 / 部门" }),
    );
    await user.click(screen.getByRole("combobox", { name: "所属学校" }));
    await user.click(await screen.findByText("学校甲"));
    const input = screen.getByRole("textbox", { name: "名称" });
    await user.type(input, "连续提交");
    const form = input.closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(csrf).toHaveBeenCalledTimes(1));
    release({ headerName: "X-CSRF-TOKEN", token: "token" });
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(post).toHaveBeenCalledTimes(1);
  });
});
