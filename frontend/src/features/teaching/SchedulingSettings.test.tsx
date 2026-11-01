import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BlocksTab, PolicyTab } from "./SchedulingSettings";

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
