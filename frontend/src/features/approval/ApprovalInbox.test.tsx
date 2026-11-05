import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApprovalInbox, PolicyEditor } from "./ApprovalInbox";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

const capabilities = {
  canRead: true,
  canManage: true,
  canDecide: true,
  currentAccountId: "user-a",
  schools: [
    { id: "school-1", name: "测试学校", canManage: true, canDecide: true },
  ],
};

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

it("待审批列表展示金额与动作，通过时携带版本", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/approval/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/approval/policies")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "policy-1",
            schoolId: "school-1",
            action: "REFUND_HOURS",
            freeBelow: "100.00",
            active: true,
            version: 2,
          },
        ],
      };
    if (path === "/api/approval/requests")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "request-1",
            schoolId: "school-1",
            action: "REFUND_HOURS",
            enrollmentId: "enrollment-1",
            amount: "200.00",
            reason: "部分退课",
            status: "PENDING",
            requestedBy: "user-b",
            version: 0,
          },
        ],
      };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<ApprovalInbox onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("200.00")).toBeTruthy();
  expect(screen.getAllByText("退课时").length).toBeGreaterThan(0);
  expect(screen.getByText("100.00")).toBeTruthy();
  await user.click(screen.getByRole("button", { name: /通\s*过/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/approval/requests/{id}/decide");
  expect(post.mock.calls[0][1].body).toMatchObject({
    expectedVersion: 0,
    decision: "APPROVED",
  });
  expect(post.mock.calls[0][1].params.path.id).toBe("request-1");
});

it("调整免批额度时提交版本，冲突提示重新读取", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: false, status: 409 } });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <PolicyEditor
      schoolId="school-1"
      policy={{
        id: "policy-1",
        schoolId: "school-1",
        action: "CASH_REFUND",
        freeBelow: "0.00",
        active: true,
        version: 4,
      }}
      onCancel={vi.fn()}
      onSaved={vi.fn()}
    />,
  );
  const input = screen.getByRole("spinbutton", { name: "免批额度（元）" });
  await user.clear(input);
  await user.type(input, "300");
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toMatchObject({
    schoolId: "school-1",
    action: "CASH_REFUND",
    freeBelow: "300",
    expectedVersion: 4,
  });
  await screen.findByText(/策略已被他人修改/);
});

it("申请人可以按版本撤回自己的待审批申请", async () => {
  vi.clearAllMocks();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/approval/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/approval/policies")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/approval/requests")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "request-2",
            schoolId: "school-1",
            action: "ENROLLMENT_DEFER",
            enrollmentId: "enrollment-1",
            amount: "0.00",
            reason: "延期申请",
            requestedUntil: "2026-09-01",
            status: "PENDING",
            requestedBy: "user-a",
            version: 3,
          },
        ],
      };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<ApprovalInbox onUnauthorized={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: /撤\s*回/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/approval/requests/{id}/withdraw");
  expect(post.mock.calls[0][1].params.path.id).toBe("request-2");
  expect(post.mock.calls[0][1].body).toEqual({ expectedVersion: 3 });
});

it("审批申请遭遇网络中断后重试复用同一请求标识", async () => {
  vi.clearAllMocks();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/approval/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/approval/policies")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/approval/requests")
      return { response: { ok: true, status: 200 }, data: [] };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post
    .mockRejectedValueOnce(new Error("network timeout"))
    .mockResolvedValueOnce({ response: { ok: true, status: 201 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<ApprovalInbox onUnauthorized={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: "申请审批" }));
  await user.click(screen.getByLabelText("动作"));
  await user.click(await screen.findByText("延期"));
  await user.type(screen.getByLabelText("报名编号"), "enrollment-1");
  await user.type(screen.getByLabelText("申请延期至"), "2026-09-01");
  await user.click(screen.getByRole("button", { name: "提交申请" }));
  await screen.findByText("申请审批时网络中断，请刷新后核对申请状态。");
  await user.click(screen.getByRole("button", { name: "提交申请" }));

  await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
  const first = post.mock.calls[0][1].body;
  const retry = post.mock.calls[1][1].body;
  expect(first).toMatchObject({
    action: "ENROLLMENT_DEFER",
    enrollmentId: "enrollment-1",
    amount: "0",
    requestedUntil: "2026-09-01",
  });
  expect(first.requestId).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
  );
  expect(retry.requestId).toBe(first.requestId);
});

it("已通过的延期审批可执行并使用审批单中的截止日", async () => {
  vi.clearAllMocks();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/approval/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/approval/policies")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/approval/requests")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "defer-approved",
            schoolId: "school-1",
            action: "ENROLLMENT_DEFER",
            enrollmentId: "enrollment-1",
            amount: "0.00",
            reason: "补课安排",
            requestedUntil: "2026-09-01",
            status: "APPROVED",
            requestedBy: "user-b",
            version: 1,
          },
        ],
      };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(<ApprovalInbox onUnauthorized={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: "执行延期" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/enrollment/enrollments/{id}/defer");
  expect(post.mock.calls[0][1].params.path.id).toBe("enrollment-1");
  expect(post.mock.calls[0][1].body).toEqual({
    approvalId: "defer-approved",
    validUntil: "2026-09-01",
  });
});
