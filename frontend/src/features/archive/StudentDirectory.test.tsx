import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StudentCreator, StudentDirectory } from "./StudentDirectory";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({
  api: { GET: get, POST: post },
  csrf,
}));

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.unstubAllGlobals();
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
    if (path === "/api/archive/students/counts")
      return {
        response: { ok: true, status: 200 },
        data: { total: 1, active: 1, inactive: 0, thisMonth: 1 },
      };
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

it("通过持久导出任务展示进度并在完成后下载", { timeout: 20000 }, async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/archive/capabilities")
      return {
        response: { ok: true, status: 200 },
        data: {
          ...capabilities,
          canExport: true,
          exportableSchools: [{ id: "school-1", name: "测试学校" }],
        },
      };
    if (path === "/api/archive/students/counts")
      return {
        response: { ok: true, status: 200 },
        data: { total: 0, active: 0, inactive: 0, thisMonth: 0 },
      };
    if (path === "/api/archive/students")
      return {
        response: { ok: true, status: 200 },
        data: { items: [], page: 0, size: 20, total: 0 },
      };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "csrf-token" });
  const requestIds = vi
    .fn()
    .mockReturnValueOnce("request-export-1")
    .mockReturnValueOnce("request-export-2");
  vi.stubGlobal("crypto", { randomUUID: requestIds });
  const task = {
    id: "export-task-1",
    schoolId: "school-1",
    format: "CSV",
    status: "COMPLETED",
    attemptCount: 1,
    rowCount: 4,
    errorCode: null,
    createdAt: "2026-11-05T00:00:00Z",
    startedAt: null,
    finishedAt: null,
  };
  const completed = task;
  const fetchMock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith("/api/exports?"))
        return Response.json([], { status: 200 });
      if (url === "/api/exports" && init?.method === "POST") {
        const body = JSON.parse(String(init.body)) as { format: string };
        return Response.json({ ...task, format: body.format }, { status: 202 });
      }
      if (url === "/api/exports/export-task-1/download")
        return new Response(new Blob(["test"]), { status: 200 });
      if (url.startsWith("/api/exports/"))
        return Response.json(completed, { status: 200 });
      throw new Error(`unexpected fetch ${url}`);
    },
  );
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("URL", {
    createObjectURL: () => "blob:test",
    revokeObjectURL: () => {},
  });
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderPage(<StudentDirectory onUnauthorized={vi.fn()} />);

  await user.click(await screen.findByRole("button", { name: "导出" }));
  await user.click(await screen.findByText("导出 CSV"));
  expect(
    await screen.findByText(
      "CSV 导出任务：完成，共 4 条",
      {},
      { timeout: 5000 },
    ),
  ).toBeTruthy();
  await user.click(screen.getByRole("button", { name: /下\s*载/ }));
  expect(fetchMock).toHaveBeenCalledWith(
    "/api/exports/export-task-1/download",
    { credentials: "same-origin" },
  );
  expect(fetchMock).toHaveBeenCalledWith(
    "/api/exports",
    expect.objectContaining({ method: "POST" }),
  );
  await user.click(screen.getByRole("button", { name: "导出" }));
  await user.click(await screen.findByText("导出 PDF"));
  expect(await screen.findByText("PDF 导出任务：完成，共 4 条")).toBeTruthy();
  const createBodies = fetchMock.mock.calls
    .filter(([, init]) => init?.method === "POST")
    .map(([, init]) => JSON.parse(String(init?.body)) as { format: string });
  expect(createBodies.map(({ format }) => format)).toEqual(["CSV", "PDF"]);
});

it("重新打开学员目录时恢复最近的持久导出任务", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/archive/capabilities")
      return {
        response: { ok: true, status: 200 },
        data: {
          ...capabilities,
          canExport: true,
          exportableSchools: [{ id: "school-1", name: "测试学校" }],
        },
      };
    if (path === "/api/archive/students/counts")
      return {
        response: { ok: true, status: 200 },
        data: { total: 0, active: 0, inactive: 0, thisMonth: 0 },
      };
    if (path === "/api/archive/students")
      return {
        response: { ok: true, status: 200 },
        data: { items: [], page: 0, size: 20, total: 0 },
      };
    throw new Error("unexpected path " + path);
  });
  const recentTask = {
    id: "export-history-1",
    schoolId: "school-1",
    format: "XLSX",
    status: "COMPLETED",
    attemptCount: 1,
    rowCount: 8,
    errorCode: null,
    createdAt: "2026-11-06T00:00:00Z",
    startedAt: "2026-11-06T00:00:01Z",
    finishedAt: "2026-11-06T00:00:02Z",
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json([recentTask], { status: 200 })),
  );
  renderPage(<StudentDirectory onUnauthorized={vi.fn()} />);

  expect(await screen.findByText("XLSX 导出任务：完成，共 8 条")).toBeTruthy();
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
