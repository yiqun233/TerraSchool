import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BlocksTab, PolicyTab, SchoolCalendarTab } from "./SchedulingSettings";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

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

it("策略页展示当前值并在保存时携带版本", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "t" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const saved = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <PolicyTab
      schoolId="school-1"
      canMaintain
      policy={{
        schoolId: "school-1",
        blockTeacherUnavailable: true,
        blockRoomUnavailable: false,
        minGapMinutes: 15,
        version: 3,
      }}
      onError={vi.fn()}
      onMessage={vi.fn()}
      onSaved={saved}
    />,
  );
  const gap = screen.getByRole("spinbutton");
  expect((gap as HTMLInputElement).value).toBe("15");
  await user.clear(gap);
  await user.type(gap, "30");
  await user.click(screen.getByRole("button", { name: /保存策略/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/schedule-policy");
  expect(post.mock.calls[0][1].body).toMatchObject({
    blockTeacherUnavailable: true,
    blockRoomUnavailable: false,
    minGapMinutes: 30,
    expectedVersion: 3,
  });
  expect(saved).toHaveBeenCalled();
});

it("不可用时间列表展示资源与时段，无维护权限时不显示新增表单", async () => {
  vi.clearAllMocks();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/teaching/resource-blocks")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "b1",
            teacherId: "t1",
            teacherName: "张老师",
            blockDate: "2026-05-02",
            startsAt: "09:00",
            endsAt: "18:00",
            reason: "休假",
          },
        ],
      };
    return { response: { ok: true, status: 200 }, data: [] };
  });
  renderWithClient(
    <BlocksTab
      schoolId="school-1"
      canMaintain={false}
      onError={vi.fn()}
      onMessage={vi.fn()}
    />,
  );
  expect(await screen.findByText("教师：张老师")).toBeTruthy();
  expect(screen.getByText("休假")).toBeTruthy();
  expect(screen.queryByRole("button", { name: /新\s*增/ })).toBeNull();
});

it("登记停课日并按版本取消，保留校历结果", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "t" });
  get.mockResolvedValue({
    response: { ok: true, status: 200 },
    data: [
      {
        id: "closure-1",
        schoolId: "school-1",
        closedOn: "2026-11-14",
        reason: "校历休息日",
        active: true,
        version: 0,
      },
    ],
  });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <SchoolCalendarTab
      schoolId="school-1"
      canMaintain
      onError={vi.fn()}
      onMessage={vi.fn()}
    />,
  );
  expect(await screen.findByText("校历休息日")).toBeTruthy();
  await user.type(screen.getByLabelText("停课日期"), "2026-11-14");
  await user.type(screen.getByLabelText("原因"), "校历休息日");
  await user.click(screen.getByRole("button", { name: "登记停课日" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/teaching/school-closures");
  expect(post.mock.calls[0][1].body).toEqual({
    schoolId: "school-1",
    closedOn: "2026-11-14",
    reason: "校历休息日",
  });
  await user.click(screen.getByRole("button", { name: /取\s*消/ }));
  await user.click(await screen.findByRole("button", { name: "取消停课" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
  expect(post.mock.calls[1][0]).toBe(
    "/api/teaching/school-closures/{id}/cancel",
  );
  expect(post.mock.calls[1][1].body).toEqual({ expectedVersion: 0 });
});
