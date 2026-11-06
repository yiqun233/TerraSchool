import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  AttendanceModal,
  CloseLessonButton,
  PublishLessonButton,
  MakeupLessonModal,
  RescheduleLessonModal,
  SwapLessonModal,
  TeachingWorkspace,
} from "./TeachingWorkspace";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

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

const lesson = {
  id: "lesson-1",
  classId: "class-1",
  lessonDate: "2026-03-02",
  startsAt: "09:00",
  endsAt: "10:00",
  teacherName: "教师甲",
  roomName: "教室甲",
  topic: "第一课",
  hours: "1.00",
  status: "DRAFT",
  version: 0,
  attendanceCount: 0,
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
  await user.click((await screen.findAllByText("数学 A 班"))[0]);
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
  await user.click(await screen.findByText("数学 B 班 · 数学"));
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
});

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

it("空班取消需填写原因并提交版本及幂等身份", async () => {
  vi.clearAllMocks();
  vi.stubGlobal(
    "prompt",
    vi.fn(() => "招生计划调整"),
  );
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "empty-class",
            name: "待取消空班",
            status: "ACTIVE",
            memberCount: 0,
            version: 3,
          },
        ],
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
  await user.click(await screen.findByRole("button", { name: "取消空班" }));
  await user.click(await screen.findByRole("button", { name: "确认取消" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/classes/{id}/cancel");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "empty-class" } },
    body: {
      expectedVersion: 3,
      reason: "招生计划调整",
      requestId: expect.any(String),
    },
  });
  vi.unstubAllGlobals();
});

it("发布课次携带读取版本", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <PublishLessonButton
      lesson={lesson as never}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByRole("button", { name: /发\s*布/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/lessons/{id}/publish");
  expect(post.mock.calls[0][1].body).toEqual({ expectedVersion: 0 });
  expect(done).toHaveBeenCalled();
});

it("调课提交版本、原因与幂等标识", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <RescheduleLessonModal
      lesson={{ ...lesson, status: "PUBLISHED", version: 5 } as never}
      onCancel={vi.fn()}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.clear(screen.getByRole("textbox", { name: "调课原因" }));
  await user.type(
    screen.getByRole("textbox", { name: "调课原因" }),
    "场地维护",
  );
  await user.click(screen.getByRole("button", { name: "保存调课" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/lessons/{id}/reschedule");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "lesson-1" } },
    body: {
      expectedVersion: 5,
      lessonDate: "2026-03-02",
      startsAt: "09:00",
      endsAt: "10:00",
      reason: "场地维护",
      requestId: expect.any(String),
    },
  });
  expect(done).toHaveBeenCalled();
});

it("换课提交两课次版本与稳定请求标识", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <SwapLessonModal
      lesson={{ ...lesson, status: "PUBLISHED", version: 2 } as never}
      candidates={
        [
          {
            ...lesson,
            id: "lesson-2",
            lessonDate: "2026-03-09",
            status: "PUBLISHED",
            version: 4,
          },
        ] as never
      }
      onCancel={vi.fn()}
      onDone={vi.fn()}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByRole("combobox", { name: "换课对象" }));
  await user.click(await screen.findByText(/2026-03-09/));
  await user.type(
    screen.getByRole("textbox", { name: "换课原因" }),
    "教师排班调整",
  );
  await user.click(screen.getByRole("button", { name: "确认换课" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/lessons/{id}/swap");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "lesson-1" } },
    body: {
      otherLessonId: "lesson-2",
      expectedVersion: 2,
      expectedOtherVersion: 4,
      reason: "教师排班调整",
      requestId: expect.any(String),
    },
  });
});

it("补课表单提交原课次与日期时段原因", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 201 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <MakeupLessonModal
      lesson={
        { ...lesson, status: "CLOSED", lessonDate: "2026-03-05" } as never
      }
      onCancel={vi.fn()}
      onDone={vi.fn()}
      onError={vi.fn()}
    />,
  );
  await user.type(
    screen.getByRole("textbox", { name: "补课原因" }),
    "病假补课",
  );
  await user.click(screen.getByRole("button", { name: "创建补课草稿" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/lessons/{id}/makeups");
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "lesson-1" } },
    body: {
      lessonDate: "2026-03-06",
      startsAt: "09:00",
      endsAt: "10:00",
      reason: "病假补课",
      requestId: expect.any(String),
    },
  });
});

it("考勤确认提交全部在班报名，关闭课次单独提交版本", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <AttendanceModal
      lesson={
        {
          ...lesson,
          startsAt: "09:00",
          endsAt: "10:00",
          status: "PUBLISHED",
          version: 3,
        } as never
      }
      members={members as never}
      onCancel={vi.fn()}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByLabelText("出勤-学员乙"));
  await user.click(screen.getAllByText("缺勤")[1]);
  await user.click(screen.getAllByText("部分缺勤")[0]);
  await user.type(screen.getByLabelText("实际出勤分钟-学员甲"), "30");
  await user.click(screen.getByRole("button", { name: /确认考勤/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  const payload = post.mock.calls[0][1].body;
  expect(post.mock.calls[0][0]).toBe("/api/teaching/lessons/{id}/attendance");
  expect(payload.expectedVersion).toBe(3);
  expect(payload.records).toHaveLength(2);
  expect(
    payload.records.find(
      (item: { enrollmentId: string }) => item.enrollmentId === "enrollment-2",
    ).status,
  ).toBe("ABSENT");
  expect(
    payload.records.find(
      (item: { enrollmentId: string }) => item.enrollmentId === "enrollment-1",
    ),
  ).toMatchObject({ status: "PARTIAL", attendedMinutes: 30 });
  expect(done).toHaveBeenCalled();

  renderWithClient(
    <CloseLessonButton
      lesson={{ ...lesson, status: "CONFIRMED", version: 4 } as never}
      onDone={vi.fn()}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getAllByRole("button", { name: /关闭课次/ })[0]);
  await waitFor(() =>
    expect(
      post.mock.calls.some(
        (call) => call[0] === "/api/teaching/lessons/{id}/close",
      ),
    ).toBe(true),
  );
  const closeCall = post.mock.calls.find(
    (call) => call[0] === "/api/teaching/lessons/{id}/close",
  );
  expect(closeCall![1].body).toEqual({ expectedVersion: 4 });
});

it("补课考勤默认只将原缺勤学员标记为出勤", () => {
  renderWithClient(
    <AttendanceModal
      lesson={
        {
          ...lesson,
          status: "PUBLISHED",
          makeupSourceLessonId: "source-lesson",
          makeupEnrollmentIds: ["enrollment-1"],
        } as never
      }
      members={members as never}
      onCancel={vi.fn()}
      onDone={vi.fn()}
      onError={vi.fn()}
    />,
  );
  const eligible = screen.getByLabelText("出勤-学员甲");
  const ineligible = screen.getByLabelText("出勤-学员乙");
  expect(
    (eligible.querySelector('input[value="PRESENT"]') as HTMLInputElement)
      .checked,
  ).toBe(true);
  expect(
    (ineligible.querySelector('input[value="ABSENT"]') as HTMLInputElement)
      .checked,
  ).toBe(true);
});

it("展示逐报名累计消费与剩余课时对账", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return { response: { ok: true, status: 200 }, data: [] };
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
          total: 0,
          page: 0,
          pageSize: 100,
        },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return {
        response: { ok: true, status: 200 },
        data: {
          items: [
            {
              enrollmentId: "enrollment-1",
              studentId: "student-1",
              studentName: "学员甲",
              enrollmentStatus: "ACTIVE",
              settlementMode: 1,
              price: "1000.00",
              purchasedHours: "10.00",
              consumedHours: "2.00",
              consumedAmount: "200.00",
              cancelledHours: "1.00",
              remainingHours: "7.00",
            },
          ],
          page: 0,
          size: 50,
          total: 1,
        },
      };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "2026-10-05", to: "2026-11-04", lessons: [] },
      };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("学员消费与课时对账")).toBeTruthy();
  expect(await screen.findByText("学员甲")).toBeTruthy();
  expect(screen.getByText("200.00")).toBeTruthy();
  expect(screen.getByText("7.00")).toBeTruthy();
  expect(screen.getByText(/教务、报名与资金读取权限/)).toBeTruthy();
});

it("按课次展示考勤状态统计", async () => {
  vi.clearAllMocks();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: { classes: [], lessons: [] },
      };
    if (path === "/api/teaching/reports/resource-daily-schedule")
      return {
        response: { ok: true, status: 200 },
        data: { lessons: [], missingTeacher: 0, missingRoom: 0 },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: {
          from: "2026-10-05",
          to: "2026-11-04",
          lessons: [
            {
              lessonId: "report-lesson",
              className: "统计班",
              lessonDate: "2026-11-04",
              startsAt: "09:00",
              endsAt: "10:00",
              lessonStatus: "CLOSED",
              present: 3,
              partial: 1,
              absent: 4,
              leave: 2,
              recorded: 10,
            },
          ],
        },
      };
    throw new Error("unexpected path " + path);
  });

  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("课次考勤统计")).toBeTruthy();
  expect(await screen.findByText("统计班")).toBeTruthy();
  expect(screen.getByText("部分缺勤")).toBeTruthy();
  expect(screen.getByText("10")).toBeTruthy();
});

it("按学校展示教务综合候选汇总并明确期间口径", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/reports/overview")
      return {
        response: { ok: true, status: 200 },
        data: {
          from: "2026-10-01",
          to: "2026-10-31",
          activeClasses: 3,
          closedClasses: 1,
          cancelledClasses: 0,
          lessons: 7,
          draftLessons: 1,
          publishedLessons: 2,
          confirmedLessons: 1,
          closedLessons: 3,
          present: 8,
          partial: 1,
          absent: 2,
          leave: 0,
          recorded: 11,
        },
      };
    if (path === "/api/teaching/classes")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: { classes: [], lessons: [] },
      };
    if (path === "/api/teaching/reports/resource-daily-schedule")
      return {
        response: { ok: true, status: 200 },
        data: {
          day: "2026-10-31",
          missingTeacher: 0,
          missingRoom: 0,
          lessons: [],
        },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "2026-10-01", to: "2026-10-31", lessons: [] },
      };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);

  await userEvent
    .setup({ pointerEventsCheck: 0 })
    .click(await screen.findByRole("button", { name: "加载教务汇总" }));

  expect(await screen.findByText("教务综合情况（候选口径）")).toBeTruthy();
  expect(
    await screen.findByText("课次状态（2026-10-01 至 2026-10-31）"),
  ).toBeTruthy();
  expect(
    await screen.findByText("候选数据待 Q62 确认。", { exact: false }),
  ).toBeTruthy();
  expect(get).toHaveBeenCalledWith(
    "/api/teaching/reports/overview",
    expect.objectContaining({
      params: expect.objectContaining({
        query: expect.objectContaining({ schoolId: "school-1" }),
      }),
    }),
  );
});

it("同课程转班提交已批准单据和稳定请求身份", async () => {
  vi.clearAllMocks();
  vi.spyOn(window, "prompt").mockReturnValue("同课程调整");
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "csrf-token" });
  post.mockResolvedValue({ response: { ok: true, status: 200 } });
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/teaching/classes")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "source-class",
            schoolId: "school-1",
            curriculumId: "curriculum-1",
            name: "原班",
            status: "ACTIVE",
            memberCount: 1,
          },
          {
            id: "target-class",
            schoolId: "school-1",
            curriculumId: "curriculum-1",
            name: "目标班",
            status: "ACTIVE",
            memberCount: 0,
          },
        ],
      };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: { classes: [], lessons: [] },
      };
    if (path === "/api/teaching/lessons")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/teaching/classes/{id}/members")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            enrollmentId: "enrollment-1",
            studentName: "转班学员",
            joinedOn: "2026-01-01",
            status: "ACTIVE",
          },
        ],
      };
    if (path === "/api/teaching/reports/resource-daily-schedule")
      return {
        response: { ok: true, status: 200 },
        data: { lessons: [], missingTeacher: 0, missingRoom: 0 },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "", to: "", lessons: [] },
      };
    throw new Error("unexpected path " + path);
  });

  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  await user.click(await screen.findByText("原班"));
  await user.selectOptions(
    await screen.findByRole("combobox", { name: "转入目标班级" }),
    "target-class",
  );
  await user.type(
    screen.getByRole("textbox", { name: "已批准转班审批单号" }),
    "approval-1",
  );
  expect(await screen.findByText("转班学员")).toBeTruthy();
  expect(screen.getByText("课时对账")).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: /转\s*班/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(false);
  await user.click(await screen.findByRole("button", { name: /转\s*班/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post).toHaveBeenCalledWith(
    "/api/teaching/enrollments/{id}/transfer",
    expect.objectContaining({
      params: { path: { id: "enrollment-1" } },
      body: expect.objectContaining({
        expectedFromClassId: "source-class",
        toClassId: "target-class",
        approvalId: "approval-1",
        reason: "同课程调整",
        requestId: expect.any(String),
      }),
    }),
  );
});

it("退班提交已批准单据并沿用稳定请求身份", async () => {
  vi.clearAllMocks();
  vi.spyOn(window, "prompt")
    .mockReturnValueOnce("withdrawal-approval")
    .mockReturnValueOnce("家庭原因");
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "csrf-token" });
  post.mockResolvedValue({ response: { ok: true, status: 200 } });
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
            curriculumId: "curriculum-1",
            name: "退班测试班",
            status: "ACTIVE",
            memberCount: 1,
          },
        ],
      };
    if (path === "/api/teaching/me/workbench")
      return {
        response: { ok: true, status: 200 },
        data: { classes: [], lessons: [] },
      };
    if (path === "/api/teaching/lessons")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/teaching/classes/{id}/members")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            enrollmentId: "enrollment-1",
            studentName: "退班学员",
            joinedOn: "2026-01-01",
            status: "ACTIVE",
          },
        ],
      };
    if (path === "/api/teaching/reports/resource-daily-schedule")
      return {
        response: { ok: true, status: 200 },
        data: { lessons: [], missingTeacher: 0, missingRoom: 0 },
      };
    if (path === "/api/teaching/reports/student-consumption")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/teaching/reports/attendance")
      return {
        response: { ok: true, status: 200 },
        data: { from: "", to: "", lessons: [] },
      };
    throw new Error("unexpected path " + path);
  });

  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  await user.click(await screen.findByText("退班测试班"));
  expect(await screen.findByText("退班学员")).toBeTruthy();
  await user.click(await screen.findByRole("button", { name: /退\s*班/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post).toHaveBeenCalledWith(
    "/api/teaching/enrollments/{id}/withdraw",
    expect.objectContaining({
      params: { path: { id: "enrollment-1" } },
      body: expect.objectContaining({
        classId: "class-1",
        approvalId: "withdrawal-approval",
        reason: "家庭原因",
        requestId: expect.any(String),
      }),
    }),
  );
});
