import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SchoolEditor } from "./SchoolEditor";
const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
it("停用学校时提交读取版本，冲突后阻止覆盖", async () => {
  get.mockResolvedValue({
    response: { ok: true },
    data: { id: "s", name: "学校甲", active: true, version: 7 },
  });
  post.mockResolvedValue({ response: { ok: false, status: 409 } });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "token" });
  const user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SchoolEditor
        school={{
          id: "s",
          kind: "SCHOOL",
          name: "学校甲",
          schoolId: "s",
          version: 7,
        }}
        onSaved={vi.fn()}
      />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "管理学校" }));
  await user.click(await screen.findByRole("switch", { name: "学校启用" }));
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await screen.findByText("学校已被修改，请关闭后重新读取。");
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toEqual({
    expectedVersion: 7,
    name: "学校甲",
    active: false,
  });
  expect(
    (screen.getByRole("button", { name: /保\s*存/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
