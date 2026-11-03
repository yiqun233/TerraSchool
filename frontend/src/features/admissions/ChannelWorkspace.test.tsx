import { expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ChannelWorkspace } from "./ChannelWorkspace";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
function setup(
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <ChannelWorkspace accountKey="tenant:user" onUnauthorized={vi.fn()} />
      </QueryClientProvider>,
    ),
  };
}
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "test" });
  get.mockImplementation(async (path: string) =>
    path.endsWith("/capabilities")
      ? {
          response: { ok: true },
          data: { schools: [{ id: "s1", name: "学校一", maintainable: true }] },
        }
      : {
          response: { ok: true },
          data: {
            items: [
              {
                id: "c1",
                schoolId: "s1",
                name: "已有渠道",
                active: true,
                version: 0,
              },
            ],
            hasMore: false,
          },
        },
  );
});

it("创建结果不确定时跨重挂载保留身份，重试拒绝也不丢失", async () => {
  post
    .mockResolvedValueOnce({ response: { ok: false, status: 503 } })
    .mockResolvedValueOnce({ response: { ok: false, status: 403 } })
    .mockResolvedValueOnce({ response: { ok: true, status: 200 } });
  const user = userEvent.setup();
  const first = setup();
  await user.type(await screen.findByLabelText("渠道名称"), "社群推荐");
  await user.click(screen.getByRole("button", { name: "新建渠道" }));
  await screen.findByText(/结果未确认，请保留当前内容/);
  first.unmount();
  setup();
  await screen.findByText(/创建结果待确认/);
  await user.click(await screen.findByRole("button", { name: "重试创建" }));
  await screen.findByText(/首次结果仍未确认/);
  expect(localStorage.length).toBe(1);
  await user.click(screen.getByRole("button", { name: "重试创建" }));
  await screen.findByText("渠道已创建。");
  expect(post.mock.calls[0][1].body).toEqual(post.mock.calls[1][1].body);
  expect(post.mock.calls[0][1].body).toEqual(post.mock.calls[2][1].body);
  expect(localStorage.length).toBe(0);
});

it("渠道读取撤权后隐藏旧列表与维护入口", async () => {
  const { client } = setup();
  expect(await screen.findByText("已有渠道")).toBeTruthy();
  get.mockImplementation(async (path: string) =>
    path.endsWith("/capabilities")
      ? {
          response: { ok: true },
          data: { schools: [{ id: "s1", name: "学校一", maintainable: true }] },
        }
      : { response: { ok: false, status: 403 } },
  );
  await act(async () => {
    await client.invalidateQueries({ queryKey: ["channels"] });
  });
  await screen.findByText(/无法读取渠道，请检查学校范围/);
  await waitFor(() => expect(screen.queryByText("已有渠道")).toBeNull());
  expect(screen.queryByRole("button", { name: "新建渠道" })).toBeNull();
});
