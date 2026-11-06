import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  ClassCapacityEditor,
  ClassCreator,
  TeachingWorkspace,
} from "./TeachingWorkspace";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

afterEach(() => vi.unstubAllGlobals());

/**
 * 整页工作台每次渲染都会重建多张表格，逐字输入会把单个用例推到 20 秒上限；
 * 文本字段用一次 change 事件写入，点击与下拉仍走真实交互。
 */
function fillText(element: HTMLElement, value: string) {
  fireEvent.change(element, { target: { value } });
}

const capabilities = {
  canRead: true,
  canMaintain: true,
  canRecordAttendance: true,
  canCloseLesson: true,
  schools: [
    {
      id: "school-1",
      name: "测试学校",
      maintainable: true,
      canRecordAttendance: true,
      canCloseLesson: true,
    },
  ],
};

const members = [
  {
    enrollmentId: "enrollment-1",
    studentName: "学员甲",
    joinedOn: "2026-02-01",
    status: "ACTIVE",
  },
  {
    enrollmentId: "enrollment-2",
    studentName: "学员乙",
    joinedOn: "2026-02-01",
    status: "ACTIVE",
  },
];

function renderWithClient(ui: React.ReactElement) {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      {ui}
    </QueryClientProvider>,
  );
}

it("新建班级可设置名额上限并提交", async () => {
  const user = userEvent.setup();
  const onCreated = vi.fn();
  post.mockReset();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "token" });
  post.mockResolvedValue({ response: { ok: true, status: 201 } });
  renderWithClient(
    <ClassCreator
      schoolId="school-1"
      curricula={[{ id: "curriculum-1", name: "少儿英语" }]}
      onCancel={vi.fn()}
      onCreated={onCreated}
    />,
  );
  fillText(screen.getByRole("textbox", { name: "班级名称" }), "小班");
  fillText(screen.getByRole("spinbutton", { name: "名额上限" }), "12");
  await user.click(screen.getByRole("combobox", { name: "课程" }));
  await user.click(await screen.findByText("少儿英语"));
  await user.click(screen.getByRole("button", { name: "创建班级" }));
  await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toMatchObject({
    schoolId: "school-1",
    curriculumId: "curriculum-1",
    name: "小班",
    capacity: 12,
  });
});

it("调整班级名额提交版本、请求身份、原因和新的容量", async () => {
  const user = userEvent.setup();
  const onUpdated = vi.fn();
  post.mockReset();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "token" });
  post.mockResolvedValue({ response: { ok: true, status: 200 } });
  renderWithClient(
    <ClassCapacityEditor
      classView={{
        id: "class-1",
        name: "少儿英语A班",
        memberCount: 4,
        capacity: 8,
        version: 3,
      }}
      onCancel={vi.fn()}
      onUpdated={onUpdated}
      onUnauthorized={vi.fn()}
    />,
  );
  await user.clear(screen.getByRole("spinbutton", { name: "名额上限" }));
  fillText(screen.getByRole("spinbutton", { name: "名额上限" }), "12");
  fillText(screen.getByRole("textbox", { name: "调整原因" }), "新增名额");
  await user.click(screen.getByRole("button", { name: "保存名额" }));
  await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/classes/{id}/capacity");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "class-1" } },
    body: {
      expectedVersion: 3,
      capacity: 12,
      reason: "新增名额",
    },
  });
  expect(post.mock.calls[0][1].body.requestId).toEqual(expect.any(String));
});

it("班级列表展示在班人数与状态", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "class-1",
            schoolId: "school-1",
            schoolName: "测试学校",
            curriculumId: "curriculum-1",
            curriculumName: "少儿英语",
            name: "少儿英语A班",
            status: "ACTIVE",
            memberCount: 2,
            capacity: 12,
            version: 0,
          },
        ],
      };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: {
          classes: [
            {
              id: "class-1",
              name: "少儿英语A班",
              curriculumName: "少儿英语",
              lessonCount: 1,
            },
          ],
          lessons: [
            {
              id: "lesson-own",
              classId: "class-1",
              className: "少儿英语A班",
              curriculumName: "少儿英语",
              lessonDate: "2026-11-12",
              startsAt: "09:00",
              endsAt: "10:00",
              roomName: "教室甲",
              topic: "本人授课",
              status: "PUBLISHED",
              version: 0,
            },
          ],
        },
      };
    if (path === "/api/teaching/me/classes/{id}")
      return {
        response: { ok: true, status: 200 },
        data: {
          classInfo: {
            id: "class-1",
            name: "少儿英语A班",
            curriculumName: "少儿英语",
            status: "ACTIVE",
            groupTraining: false,
            lessonCount: 1,
          },
          members,
          lessons: [
            {
              id: "lesson-own",
              classId: "class-1",
              className: "少儿英语A班",
              curriculumName: "少儿英语",
              lessonDate: "2026-11-12",
              startsAt: "09:00",
              endsAt: "10:00",
              roomName: "教室甲",
              topic: "本人授课",
              status: "PUBLISHED",
              version: 0,
            },
          ],
        },
      };
    if (path === "/api/teaching/reports/resource-daily-schedule")
      return {
        response: { ok: true, status: 200 },
        data: {
          day: "2026-11-12",
          missingTeacher: 0,
          missingRoom: 1,
          lessons: [
            {
              lessonId: "lesson-own",
              className: "少儿英语A班",
              lessonDate: "2026-11-12",
              startsAt: "09:00",
              endsAt: "10:00",
              teacherName: "教师甲",
              roomName: null,
              topic: "资源日报课",
              status: "PUBLISHED",
            },
          ],
        },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "2026-10-05", to: "2026-11-04", lessons: [] },
      };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  expect(await screen.findAllByText("少儿英语A班")).toHaveLength(4);
  expect(screen.getByText("2")).toBeTruthy();
  expect(screen.getByText("12")).toBeTruthy();
  expect(await screen.findByText("教师本人课表与班级工作台")).toBeTruthy();
  expect(await screen.findByText("本人授课")).toBeTruthy();
  expect(await screen.findByText("资源日报课")).toBeTruthy();
  expect(screen.getByText("未分配教师 0，未分配教室 1")).toBeTruthy();
  await userEvent
    .setup({ pointerEventsCheck: 0 })
    .click(await screen.findByRole("button", { name: "查看班级工作台" }));
  expect(await screen.findByText("班级名册")).toBeTruthy();
  expect(await screen.findByText("学员甲")).toBeTruthy();
  expect(get).toHaveBeenCalledWith(
    "/api/teaching/me/classes/{id}",
    expect.objectContaining({ params: { path: { id: "class-1" } } }),
  );
});

it("可从有效报名一次选择多个目标班并提交批量入班", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "class-1",
            schoolId: "school-1",
            curriculumId: "course-1",
            curriculumName: "数学",
            name: "数学 A 班",
            status: "ACTIVE",
            memberCount: 0,
            version: 0,
          },
          {
            id: "class-2",
            schoolId: "school-1",
            curriculumId: "course-1",
            curriculumName: "数学",
            name: "数学 B 班",
            status: "ACTIVE",
            memberCount: 0,
            version: 0,
          },
        ],
      };
    if (path === "/api/enrollment/enrollments")
      return {
        response: { ok: true, status: 200 },
        data: {
          items: [
            {
              id: "enrollment-1",
              studentName: "学员甲",
              productName: "数学产品",
              curriculumName: "数学",
            },
          ],
          total: 1,
        },
      };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: { classes: [], lessons: [] },
      };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "2026-11-01", to: "2026-11-06", lessons: [] },
      };
    return { response: { ok: true, status: 200 }, data: [] };
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "token" });
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith("/api/exports?")) return Response.json([]);
    if (url === "/api/teaching/classes/members/batch")
      return Response.json([], { status: 200 });
    throw new Error(`unexpected fetch ${url}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup({ pointerEventsCheck: 0 });

  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  await user.click((await screen.findAllByText(/数学 A 班/))[0]);
  expect(await screen.findByText("课次：数学 A 班")).toBeTruthy();
  await user.click(await screen.findByRole("button", { name: "加入已有报名" }));
  expect(
    await screen.findByText(
      "选择本校有效报名加入当前班级。现有规则与审批要求仍以服务端校验为准。",
    ),
  ).toBeTruthy();
  await user.click(await screen.findByText("搜索并选择有效报名"));
  await user.click(await screen.findByText("学员甲 · 数学产品 · 数学"));
  await user.click(await screen.findByLabelText("目标班级"));
  await user.click(await screen.findByText(/数学 B 班 · 数学/));
  await user.click(screen.getByRole("button", { name: "加入班级" }));

  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/teaching/classes/members/batch",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          enrollmentId: "enrollment-1",
          classIds: ["class-1", "class-2"],
        }),
      }),
    ),
  );
}, 40_000);

it("结班需确认并提交班级版本", async () => {
  vi.clearAllMocks();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return {
        response: { ok: true, status: 200 },
        data: [{ id: "class-1", name: "待结班", status: "ACTIVE", version: 7 }],
      };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: { classes: [], lessons: [] },
      };
    if (path === "/api/teaching/reports/resource-daily-schedule")
      return {
        response: { ok: true, status: 200 },
        data: {
          day: "2026-11-04",
          lessons: [],
          missingTeacher: 0,
          missingRoom: 0,
        },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "2026-10-05", to: "2026-11-04", lessons: [] },
      };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: /结\s*班/ }));
  await user.click(await screen.findByRole("button", { name: "确认结班" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/classes/{id}/close");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "class-1" } },
    body: { expectedVersion: 7 },
  });
});
