import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
  fillText(screen.getByRole("textbox", { name: "调课原因" }), "场地维护");
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
  fillText(screen.getByRole("textbox", { name: "换课原因" }), "教师排班调整");
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
  fillText(screen.getByRole("textbox", { name: "补课原因" }), "病假补课");
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
  fillText(screen.getByLabelText("实际出勤分钟-学员甲"), "30");
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
