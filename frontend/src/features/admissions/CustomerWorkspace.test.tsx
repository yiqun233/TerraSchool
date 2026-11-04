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
    if (path.endsWith("/reminders"))
      return response({ items: [], hasMore: false });
    return response({
      items: [{ id: "p1", name: "本人客户", maskedPhone: "****1001" }],
      hasMore: false,
    });
  });
});
afterEach(() => vi.unstubAllGlobals());

it("只在完整电话授权范围显示本人待回访提醒", async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/reminders"))
      return response({
        items: [
          {
            id: "followup-1",
            customerId: "p1",
            customerName: "本人客户",
            content: "今日回访",
            occurredOn: "2026-11-01",
            nextContactOn: "2026-11-04",
          },
        ],
        hasMore: false,
      });
    if (path.endsWith("/channels"))
      return response({ items: [], hasMore: false });
    return response({ items: [], hasMore: false });
  });
  setup();
  expect(await screen.findByText("待回访提醒（本人客户）")).toBeTruthy();
  expect(await screen.findByText("今日回访")).toBeTruthy();
});

it("客户转学员时明确填写学员姓名和联系人关系，并提交幂等请求", async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/student-link")) return response(null);
    if (path.endsWith("/channels"))
      return response({ items: [], hasMore: false });
    if (path.endsWith("/reminders"))
      return response({ items: [], hasMore: false });
    return response({
      items: [
        {
          id: "lead-1",
          name: "家长甲",
          maskedPhone: "****1001",
          version: 3,
        },
      ],
      hasMore: false,
    });
  });
  post.mockResolvedValue(
    response({
      conversion: {
        customerId: "lead-1",
        studentId: "student-1",
        ownerId: "user-1",
        schoolId: "s1",
        customerVersion: 3,
      },
      creation: {
        profile: { id: "student-1", name: "学员甲" },
        possibleDuplicates: [],
        reusedContactIds: [],
      },
    }),
  );
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole("button", { name: "转为学员" }));
  await user.type(
    await screen.findByRole("textbox", { name: "学员姓名" }),
    "学员甲",
  );
  await user.click(
    await screen.findByRole("button", { name: "创建学员并关联" }),
  );
  await screen.findByText(/已关联学员档案：student-1/);
  expect(post).toHaveBeenCalledWith(
    "/api/admissions/customers/{id}/convert-to-student",
    expect.objectContaining({
      params: { path: { id: "lead-1" } },
      body: expect.objectContaining({
        requestId: "request-1",
        expectedCustomerVersion: 3,
        studentName: "学员甲",
        customerContactRelation: "GUARDIAN",
      }),
    }),
  );
  expect(localStorage.length).toBe(0);
});

it("按学校公海权限展示客户，并用版本号完成认领和转入公海", async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          {
            id: "s1",
            name: "学校一",
            maintainable: true,
            phoneReadable: true,
            poolReadable: true,
            poolManageable: true,
          },
        ],
      });
    if (path.endsWith("/pool"))
      return response({
        items: [
          {
            id: "pool-1",
            name: "公海客户",
            maskedPhone: "****1002",
            version: 4,
          },
        ],
        hasMore: false,
      });
    if (path.endsWith("/channels"))
      return response({ items: [], hasMore: false });
    if (path.endsWith("/reminders"))
      return response({ items: [], hasMore: false });
    return response({
      items: [
        {
          id: "owned-1",
          name: "本人客户",
          maskedPhone: "****1001",
          version: 2,
        },
      ],
      hasMore: false,
    });
  });
  post.mockResolvedValue(response({ id: "changed", version: 5 }));
  const user = userEvent.setup();
  setup();
  expect(await screen.findByText("学校公海")).toBeTruthy();
  await screen.findByText("公海客户");
  await user.click(await screen.findByRole("button", { name: /认\s*领/ }));
  await screen.findByText("已认领客户。");
  expect(post.mock.calls[0][0]).toBe("/api/admissions/customers/{id}/claim");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "pool-1" } },
    body: { expectedVersion: 4 },
  });
  await user.click(await screen.findByText("转入公海"));
  await screen.findByText("客户已转入公海。");
  expect(post.mock.calls[1][0]).toBe(
    "/api/admissions/customers/{id}/release-to-pool",
  );
  expect(post.mock.calls[1][1]).toMatchObject({
    params: { path: { id: "owned-1" } },
    body: { expectedVersion: 2 },
  });
});

it("可把本人同校重复客户并入主档，并从主档记录撤销", async () => {
  let merged: Array<{
    sourceId: string;
    name: string;
    version: number;
    mergedAt: string;
  }> = [];
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/channels"))
      return response({ items: [], hasMore: false });
    if (path.endsWith("/reminders"))
      return response({ items: [], hasMore: false });
    if (path.endsWith("/merges")) return response(merged);
    return response({
      items: [
        { id: "source", name: "重复线索", maskedPhone: "****1001", version: 3 },
        {
          id: "primary",
          name: "主档客户",
          maskedPhone: "****1002",
          version: 7,
        },
      ],
      hasMore: false,
    });
  });
  post
    .mockImplementationOnce(
      async (_path: string, options: { body: unknown }) => {
        expect(options.body).toMatchObject({
          primaryId: "primary",
          expectedSourceVersion: 3,
          expectedPrimaryVersion: 7,
        });
        merged = [
          {
            sourceId: "source",
            name: "重复线索",
            version: 4,
            mergedAt: "2026-11-04T10:00:00Z",
          },
        ];
        return response({
          sourceId: "source",
          primaryId: "primary",
          sourceVersion: 4,
        });
      },
    )
    .mockImplementationOnce(async () => {
      merged = [];
      return response({
        sourceId: "source",
        primaryId: "primary",
        sourceVersion: 5,
      });
    });
  const user = userEvent.setup();
  setup();
  await user.click(
    (await screen.findAllByRole("button", { name: "合并客户" }))[0],
  );
  await user.click(await screen.findByLabelText("合并主档客户"));
  await user.click((await screen.findAllByText("主档客户"))[1]);
  await user.click(await screen.findByRole("button", { name: "确认合并" }));
  expect(await screen.findByText(/客户已合并/)).toBeTruthy();
  expect(post.mock.calls[0][0]).toBe("/api/admissions/customers/{id}/merge");
  await user.click(
    (await screen.findAllByRole("button", { name: "合并记录" }))[0],
  );
  await screen.findByText("已合并的客户副档");
  await user.click(await screen.findByRole("button", { name: /撤销合并/ }));
  expect(
    await screen.findByText("已撤销合并，原客户档案已恢复到本人客户列表。"),
  ).toBeTruthy();
  expect(post.mock.calls[1][0]).toBe(
    "/api/admissions/customers/{id}/merge/reverse",
  );
});

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
  await screen.findByText("客户已录入，当前负责人可查看。");
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
  await screen.findByText("客户已录入，当前负责人可查看。");
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
