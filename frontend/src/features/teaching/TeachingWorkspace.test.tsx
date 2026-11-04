import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  AttendanceModal,
  CloseLessonButton,
  PublishLessonButton,
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
