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
  renderWorkspace();
  await user.click(await screen.findByRole("button", { name: "转为学员" }));
  fillText(await screen.findByRole("textbox", { name: "学员姓名" }), "学员甲");
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
  renderWorkspace();
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

it("可按独立权限把客户转派给同校在职负责人", async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          {
            id: "s1",
            name: "学校一",
            maintainable: true,
            assignmentManageable: true,
          },
        ],
      });
    if (path.endsWith("/assignable-employees"))
      return response([{ accountId: "account-b", name: "负责人乙" }]);
    if (path.endsWith("/channels"))
      return response({ items: [], hasMore: false });
    if (path.endsWith("/reminders"))
      return response({ items: [], hasMore: false });
    return response({
      items: [
        {
          id: "customer-1",
          name: "待转派客户",
          maskedPhone: "****1001",
          version: 3,
        },
      ],
      hasMore: false,
    });
  });
  post.mockResolvedValue(response({ id: "customer-1", version: 4 }));
  const user = userEvent.setup();
  renderWorkspace();
  await user.click(await screen.findByRole("button", { name: "转派负责人" }));
  await user.click(await screen.findByRole("combobox", { name: "新负责人" }));
  await user.click(await screen.findByText("负责人乙"));
  fillText(
    screen.getByRole("textbox", { name: "转派原因" }),
    "调整招生负责范围",
  );
  await user.click(screen.getByRole("button", { name: "确认转派" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/admissions/customers/{id}/assign-owner",
  );
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "customer-1" } },
    body: {
      requestId: "request-1",
      expectedVersion: 3,
      ownerAccountId: "account-b",
      reason: "调整招生负责范围",
    },
  });
});
