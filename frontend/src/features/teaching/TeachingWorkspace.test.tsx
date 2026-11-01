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
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<TeachingWorkspace onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("少儿英语A班")).toBeTruthy();
  expect(screen.getByText("2")).toBeTruthy();
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
      lesson={{ ...lesson, status: "PUBLISHED", version: 3 } as never}
      members={members as never}
      onCancel={vi.fn()}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByLabelText("出勤-学员乙"));
  await user.click(screen.getAllByText("缺勤")[1]);
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
