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
            requestedBy: "user-a",
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
