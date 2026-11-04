import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StudentCreator, StudentDirectory } from "./StudentDirectory";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

beforeEach(() => {
  vi.clearAllMocks();
});

const capabilities = {
  canRead: true,
  canMaintain: true,
  canReadSensitive: false,
  canAssign: true,
  maintainableSchools: [{ id: "school-1", name: "测试学校" }],
};

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderPage(ui: React.ReactElement) {
  return render(
    <QueryClientProvider client={client()}>{ui}</QueryClientProvider>,
  );
}

it("列表按服务端脱敏值展示，详情提示当前为脱敏值", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/archive/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/archive/students")
      return {
        response: { ok: true, status: 200 },
        data: {
          items: [
            {
              id: "student-1",
              studentNo: "S000001",
              name: "学员甲",
              sex: "FEMALE",
              phone: "138****5678",
              schoolId: "school-1",
              schoolName: "测试学校",
              ownerAccountId: "account-owner",
              status: "ACTIVE",
              version: 0,
              contactCount: 1,
              sensitiveFieldsMasked: true,
            },
          ],
          page: 0,
          size: 20,
          total: 1,
        },
      };
    if (path === "/api/archive/students/assignees")
      return {
        response: { ok: true, status: 200 },
        data: [{ accountId: "account-owner", name: "负责人甲" }],
      };
    if (path === "/api/archive/students/{id}")
      return {
        response: { ok: true, status: 200 },
        data: {
          id: "student-1",
          studentNo: "S000001",
          name: "学员甲",
          sex: "FEMALE",
          phone: "138****5678",
          certCode: "****1234",
          birthday: "2011-11",
          schoolId: "school-1",
          schoolName: "测试学校",
          ownerAccountId: null,
          status: "ACTIVE",
          version: 0,
          contacts: [
            {
              id: "c1",
              name: "家长甲",
              relation: "MOTHER",
              primary: true,
              phone: "137****3333",
            },
          ],
          sensitiveFieldsMasked: true,
        },
      };
    throw new Error("unexpected path " + path);
  });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderPage(<StudentDirectory onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("S000001")).toBeTruthy();
  expect(screen.getByText("138****5678")).toBeTruthy();
  expect(screen.getByText("已分派")).toBeTruthy();
  await user.click(screen.getByText("学员甲"));
  expect(await screen.findByText("当前显示脱敏值")).toBeTruthy();
  expect(screen.getByText("家长甲")).toBeTruthy();
  vi.spyOn(window, "prompt").mockReturnValue("招生负责人分派");
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "csrf-token" });
  post.mockResolvedValue({ response: { ok: true, status: 200 } });
  await user.click(screen.getByRole("combobox", { name: "学员负责人" }));
  await user.click(await screen.findByText("负责人甲"));
  await user.click(screen.getByRole("button", { name: "分派负责人" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post).toHaveBeenCalledWith(
    "/api/archive/students/{id}/owner",
    expect.objectContaining({
      params: { path: { id: "student-1" } },
      body: expect.objectContaining({
        ownerAccountId: "account-owner",
        expectedVersion: 0,
        reason: "招生负责人分派",
        requestId: expect.any(String),
      }),
    }),
  );
});

it("新建成功后展示重复提示且不再自动合并", { timeout: 15000 }, async () => {
  get.mockResolvedValue({
    response: { ok: true, status: 200 },
    data: capabilities,
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({
    response: { ok: true, status: 201 },
    data: {
      profile: { id: "student-2", studentNo: "S000002", name: "学员乙" },
      possibleDuplicates: [
        {
          studentId: "student-1",
          studentNo: "S000001",
          name: "学员甲",
          reason: "SAME_NAME_AND_BIRTHDAY",
        },
      ],
      reusedContactIds: [],
    },
  });
  const created = vi.fn(),
    user = userEvent.setup({ pointerEventsCheck: 0 });
  renderPage(
    <StudentCreator
      schools={capabilities.maintainableSchools}
      onCancel={vi.fn()}
      onCreated={created}
    />,
  );
  await user.click(screen.getByRole("combobox", { name: "归属学校" }));
  await user.click(await screen.findByText(/测试学校/));
  await user.type(screen.getByLabelText("学员姓名"), "学员乙");
  await user.type(screen.getByLabelText("出生日期"), "2013-07-08");
  await user.click(screen.getByRole("button", { name: "创建档案" }));
  await waitFor(() => expect(created).toHaveBeenCalledTimes(1));
  expect(created.mock.calls[0][1]).toHaveLength(1);
  expect(created.mock.calls[0][1][0].reason).toBe("SAME_NAME_AND_BIRTHDAY");
  expect(post.mock.calls[0][1].body.confirmDuplicate).toBe(false);
});

it(
  "结果不确定时提示保留内容重试，不清空已填信息",
  { timeout: 15000 },
  async () => {
    get.mockResolvedValue({
      response: { ok: true, status: 200 },
      data: capabilities,
    });
    csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
    post.mockResolvedValue({ response: { ok: false, status: 503 } });
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage(
      <StudentCreator
        schools={capabilities.maintainableSchools}
        onCancel={vi.fn()}
        onCreated={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "归属学校" }));
    await user.click(await screen.findByText(/测试学校/));
    await user.type(screen.getByLabelText("学员姓名"), "学员丙");
    await user.click(screen.getByRole("button", { name: "创建档案" }));
    await screen.findByText(/结果尚未确认/);
    expect((screen.getByLabelText("学员姓名") as HTMLInputElement).value).toBe(
      "学员丙",
    );
    expect(
      (screen.getByRole("button", { name: "创建档案" }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
    expect(post.mock.calls[0][1].body.requestId).toBeTruthy();
  },
);
