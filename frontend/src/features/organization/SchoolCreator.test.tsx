import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SchoolCreator } from "./SchoolCreator";
const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
it("学校创建结果不确定时保留请求身份和名称重试", async () => {
  get.mockResolvedValue({ response: { ok: true }, data: { canCreate: true } });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post
    .mockResolvedValueOnce({ response: { ok: false, status: 503 } })
    .mockResolvedValueOnce({ response: { ok: true, status: 201 } });
  const closed = vi.fn(),
    user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SchoolCreator onClosed={closed} />
    </QueryClientProvider>,
  );
  await user.click(await screen.findByRole("button", { name: "开通学校" }));
  await user.type(screen.getByLabelText("学校名称"), "新学校");
  await user.click(screen.getByRole("button", { name: "创建学校" }));
  await screen.findByText(/结果尚未确认/);
  expect((screen.getByLabelText("学校名称") as HTMLInputElement).disabled).toBe(
    true,
  );
  await user.click(screen.getByRole("button", { name: "重试相同请求" }));
  await waitFor(() => expect(closed).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toEqual(post.mock.calls[1][1].body);
});
it("安全令牌未取得时修改名称，重试提交当前名称", async () => {
  vi.clearAllMocks();
  get.mockResolvedValue({ response: { ok: true }, data: { canCreate: true } });
  csrf
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValueOnce({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 201 } });
  const user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SchoolCreator onClosed={vi.fn()} />
    </QueryClientProvider>,
  );
  await user.click(await screen.findByRole("button", { name: "开通学校" }));
  const name = screen.getByLabelText("学校名称");
  await user.type(name, "旧名称");
  await user.click(screen.getByRole("button", { name: "创建学校" }));
  await screen.findByText("无法获取安全令牌，请重试。");
  await user.clear(name);
  await user.type(name, "新名称");
  await user.click(screen.getByRole("button", { name: "创建学校" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body.name).toBe("新名称");
});
