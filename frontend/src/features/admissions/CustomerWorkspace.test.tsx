import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CustomerWorkspace } from "./CustomerWorkspace";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
const response = (data: unknown, status = 200) => ({
  response: { ok: status < 400, status },
  data,
});
async function fakeDigest(_algorithm: string, bytes: Uint8Array) {
  const result = new Uint8Array(32);
  bytes.forEach((value, index) => {
    result[index % 32] = (result[index % 32] + value + index) % 256;
  });
  return result.buffer;
}
function setup(
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <CustomerWorkspace accountKey="tenant:user" onUnauthorized={vi.fn()} />
      </QueryClientProvider>,
    ),
  };
}
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  vi.stubGlobal("crypto", {
    randomUUID: () => "request-1",
    subtle: { digest: fakeDigest },
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "test" });
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/channels"))
      return response({
        items: [{ id: "c1", name: "原渠道", active: true }],
        hasMore: false,
      });
    return response({
      items: [{ id: "p1", name: "本人客户", maskedPhone: "****1001" }],
      hasMore: false,
    });
  });
});
afterEach(() => vi.unstubAllGlobals());

it("重复提示先锁定输入，确认时使用服务端凭证并保留同一请求", async () => {
  post
    .mockResolvedValueOnce(
      response({
        status: "DUPLICATE_CONFIRMATION_REQUIRED",
        confirmationToken: "server-token",
      }),
    )
    .mockResolvedValueOnce(
      response({ status: "CREATED", customer: { id: "new" } }),
    );
  const user = userEvent.setup();
  setup();
  await user.type(await screen.findByLabelText("客户姓名"), "测试客户");
  await user.type(screen.getByLabelText("客户电话"), "13800001001");
  await user.click(screen.getByRole("button", { name: "录入客户" }));
  await screen.findByText(/发现相同号码/);
  expect((screen.getByLabelText("客户姓名") as HTMLInputElement).disabled).toBe(
    true,
  );
  expect(
    localStorage.getItem("teraschool.customer-create.tenant:user"),
  ).not.toContain("13800001001");
  await user.click(screen.getByRole("button", { name: "确认重复仍录入" }));
  await screen.findByText("客户已录入，仅创建人可查看。");
  expect(post.mock.calls[0][1].body.requestId).toBe(
    post.mock.calls[1][1].body.requestId,
  );
  expect(post.mock.calls[1][1].body.confirmationToken).toBe("server-token");
  expect(localStorage.length).toBe(0);
});

it("初次结果不确定后重挂载，原内容重试会重新取得重复确认", async () => {
  post
    .mockRejectedValueOnce(new Error("network"))
    .mockResolvedValueOnce(
      response({
        status: "DUPLICATE_CONFIRMATION_REQUIRED",
        confirmationToken: "renewed",
      }),
    )
    .mockResolvedValueOnce(
      response({ status: "CREATED", customer: { id: "created" } }),
    );
  const user = userEvent.setup();
  const first = setup();
  await user.type(await screen.findByLabelText("客户姓名"), "恢复客户");
  await user.type(screen.getByLabelText("客户电话"), "13800001002");
  await user.click(screen.getByRole("button", { name: "录入客户" }));
  await screen.findByText(/请求结果未确认/);
  first.unmount();
  setup();
  await user.type(await screen.findByLabelText("客户姓名"), "恢复客户");
  await user.type(screen.getByLabelText("客户电话"), "13800001002");
  await user.click(screen.getByRole("button", { name: "重试录入" }));
  await screen.findByText(/发现相同号码/);
  await user.click(screen.getByRole("button", { name: "确认重复仍录入" }));
  await screen.findByText("客户已录入，仅创建人可查看。");
  expect(post.mock.calls.map((call) => call[1].body.requestId)).toEqual([
    "request-1",
    "request-1",
    "request-1",
  ]);
  expect(post.mock.calls[2][1].body.confirmationToken).toBe("renewed");
});

it("学校停用且渠道不可读时仍保留原渠道身份，核对后可取消", async () => {
  const raw = new TextEncoder().encode(
    JSON.stringify(["request-1", "s1", "停校客户", "13800001003", "c1"]),
  );
  const bytes = new Uint8Array(await fakeDigest("SHA-256", raw));
  const hash = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, "0"),
  ).join("");
  localStorage.setItem(
    "teraschool.customer-create.tenant:user",
    JSON.stringify({
      requestId: "request-1",
      schoolId: "s1",
      fingerprint: hash,
      channelId: "c1",
    }),
  );
  get.mockImplementation(async (path: string) =>
    path.endsWith("/capabilities")
      ? response({
          schools: [
            {
              id: "s1",
              name: "停用学校",
              maintainable: false,
              phoneReadable: true,
            },
          ],
        })
      : path.endsWith("/channels")
        ? response(null, 403)
        : response({ items: [], hasMore: false }),
  );
  post.mockResolvedValue(response({ status: "CANCELLED" }));
  const user = userEvent.setup();
  setup();
  await user.type(await screen.findByLabelText("客户姓名"), "停校客户");
  await user.type(screen.getByLabelText("客户电话"), "13800001003");
  expect(
    (screen.getByRole("button", { name: "重试录入" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  await user.click(screen.getByRole("button", { name: "取消此请求" }));
  await screen.findByText("该请求已取消，未录入客户。");
  expect(post.mock.calls[0][0]).toBe("/api/admissions/customers/cancel");
  expect(post.mock.calls[0][1].body.channelId).toBe("c1");
  expect(localStorage.length).toBe(0);
});

it("撤销客户查看权限后隐藏旧列表", async () => {
  const { client } = setup();
  await screen.findByText("本人客户");
  get.mockImplementation(async (path: string) =>
    path.endsWith("/capabilities")
      ? response({
          schools: [
            {
              id: "s1",
              name: "学校一",
              maintainable: true,
              phoneReadable: true,
            },
          ],
        })
      : path.endsWith("/channels")
        ? response({ items: [], hasMore: false })
        : response(null, 403),
  );
  await act(async () => {
    await client.invalidateQueries({ queryKey: ["customers"] });
  });
  await screen.findByText(/无法读取本人客户/);
  await waitFor(() => expect(screen.queryByText("本人客户")).toBeNull());
  expect(screen.queryByRole("button", { name: "录入客户" })).toBeNull();
});

it("渠道查询失败时不再展示旧选项，也不允许使用旧选择录入", async () => {
  const user = userEvent.setup();
  const { client } = setup();
  await user.click(await screen.findByLabelText("客户渠道"));
  await user.click(await screen.findByText("原渠道"));
  get.mockImplementation(async (path: string) =>
    path.endsWith("/capabilities")
      ? response({
          schools: [
            {
              id: "s1",
              name: "学校一",
              maintainable: true,
              phoneReadable: true,
            },
          ],
        })
      : path.endsWith("/channels")
        ? response(null, 503)
        : response({ items: [], hasMore: false }),
  );
  await act(async () => {
    await client.invalidateQueries({ queryKey: ["customer-channel-options"] });
  });
  await screen.findByText(/无法读取渠道/);
  expect(screen.queryByLabelText("客户渠道")).toBeNull();
  expect(
    (screen.getByRole("button", { name: "录入客户" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  await user.click(screen.getByRole("button", { name: "不选择渠道" }));
  expect(
    (screen.getByRole("button", { name: "录入客户" }) as HTMLButtonElement)
      .disabled,
  ).toBe(false);
});
