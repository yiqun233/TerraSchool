import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
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

it("仅学校维护人员可打开并保存版本化自动回收规则", async () => {
  const user = userEvent.setup();
  const saved = {
    schoolId: "s1",
    enabled: true,
    inactivityDays: 7,
    assignmentAgeEnabled: false,
    assignmentAgeDays: 15,
    firstDayNoContactEnabled: false,
    firstDayCutoff: "23:59",
    version: 1,
  };
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [{ id: "s1", name: "学校一", maintainable: true }],
      });
    if (path.endsWith("/recycle-policy"))
      return response({ ...saved, enabled: false, version: 0 });
    return response({ items: [], hasMore: false });
  });
  put.mockResolvedValue(response(saved));
  renderWorkspace();
  await user.click(await screen.findByRole("button", { name: "自动回收规则" }));
  expect(await screen.findByRole("dialog")).toBeTruthy();
  fillText(screen.getByLabelText("修改原因"), "负责人确认初版规则");
  await user.click(screen.getByRole("switch", { name: "启用自动回收" }));
  await user.click(screen.getByRole("button", { name: "保存规则" }));
  await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
  expect(put.mock.calls[0]?.[0]).toBe(
    "/api/admissions/customers/recycle-policy",
  );
  expect(put.mock.calls[0]?.[1].body).toMatchObject({
    expectedVersion: 0,
    enabled: true,
    reason: "负责人确认初版规则",
  });
  expect(await screen.findByText(/规则已保存/)).toBeTruthy();
});

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
  renderWorkspace();
  expect(await screen.findByText("待回访提醒（本人客户）")).toBeTruthy();
  expect(await screen.findByText("今日回访")).toBeTruthy();
});

it("撤销客户查看权限后隐藏旧列表", async () => {
  const { client } = renderWorkspace();
  await screen.findByText("本人客户");
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/channels"))
      return response({ items: [], hasMore: false });
    return response(null, 403);
  });
  await client.invalidateQueries({ queryKey: ["customers"] });
  await screen.findByText(/无法读取本人客户/);
  await waitFor(() => expect(screen.queryByText("本人客户")).toBeNull());
  expect(screen.queryByRole("button", { name: "录入客户" })).toBeNull();
});

it("渠道查询失败时不再展示旧选项，也不允许使用旧选择录入", async () => {
  const user = userEvent.setup();
  const { client } = renderWorkspace();
  await user.click(await screen.findByLabelText("客户渠道"));
  await user.click(await screen.findByText("原渠道"));
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/channels")) return response(null, 503);
    return response({ items: [], hasMore: false });
  });
  await client.invalidateQueries({ queryKey: ["customer-channel-options"] });
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
