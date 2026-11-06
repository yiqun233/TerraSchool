import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  fakeDigest,
  fillText,
  renderWorkspace,
  resetCustomerMocks,
  response,
} from "./customerWorkspace.testkit";

const { get, post, put, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({
  api: { GET: get, POST: post, PUT: put },
  csrf,
}));

beforeEach(() => resetCustomerMocks({ get, post, put, csrf }));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
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
  renderWorkspace();
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

it("逐字输入仍然驱动录入表单的状态与校验", async () => {
  const user = userEvent.setup();
  renderWorkspace();
  const name = await screen.findByLabelText("客户姓名");
  await user.type(name, "张三");
  expect((name as HTMLInputElement).value).toBe("张三");
  await user.click(screen.getByRole("button", { name: "录入客户" }));
  expect(await screen.findByText("请填写客户姓名和联系电话。")).toBeTruthy();
  expect(post).not.toHaveBeenCalled();
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
  renderWorkspace();
  fillText(await screen.findByLabelText("客户姓名"), "测试客户");
  fillText(screen.getByLabelText("客户电话"), "13800001001");
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
  const first = renderWorkspace();
  fillText(await screen.findByLabelText("客户姓名"), "恢复客户");
  fillText(screen.getByLabelText("客户电话"), "13800001002");
  await user.click(screen.getByRole("button", { name: "录入客户" }));
  await screen.findByText(/请求结果未确认/);
  first.unmount();
  renderWorkspace();
  fillText(await screen.findByLabelText("客户姓名"), "恢复客户");
  fillText(screen.getByLabelText("客户电话"), "13800001002");
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
  renderWorkspace();
  fillText(await screen.findByLabelText("客户姓名"), "停校客户");
  fillText(screen.getByLabelText("客户电话"), "13800001003");
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
