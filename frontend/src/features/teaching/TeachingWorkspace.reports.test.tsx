import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AttendanceModal, TeachingWorkspace } from "./TeachingWorkspace";

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
  fillText(
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
