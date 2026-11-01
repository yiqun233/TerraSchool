import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  BalanceView,
  CancelHoursModal,
  ConfirmPaidModal,
  RefundPanel,
} from "./RefundPanel";

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

const balance = {
  studentId: "student-1",
  studentName: "学员甲",
  total: "200.00",
  reserved: "200.00",
  allocated: "800.00",
  available: "0.00",
};

it("账户展示总额、冻结与可用，冻结占满时不能准备退款", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/funds/balance")
      return { response: { ok: true, status: 200 }, data: balance };
    if (path === "/api/funds/refunds")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "refund-1",
            enrollmentId: "enrollment-1",
            studentId: "student-1",
            amount: "200.00",
            status: "PREPARED",
            version: 0,
            preparedAt: "2026-03-01T00:00:00Z",
          },
        ],
      };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(
    <RefundPanel
      enrollmentId="enrollment-1"
      studentId="student-1"
      onUnauthorized={vi.fn()}
      onChanged={vi.fn()}
    />,
  );
  expect((await screen.findAllByText("200.00")).length).toBeGreaterThan(0);
  const prepare = screen.getByRole("button", {
    name: /准备现金退款/,
  }) as HTMLButtonElement;
  expect(prepare.disabled).toBe(true);
  expect(screen.getByText("待支付")).toBeTruthy();
});

it("退课时提交本次课时与请求身份", async () => {
  vi.clearAllMocks();
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <CancelHoursModal
      enrollmentId="enrollment-1"
      onCancel={vi.fn()}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  const hours = screen.getByRole("spinbutton", { name: "本次退课时" });
  await user.type(hours, "2");
  await user.click(screen.getByRole("button", { name: /确认退课时/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/funds/enrollments/{id}/cancel-hours",
  );
  expect(post.mock.calls[0][1].body.hours).toBe("2");
  expect(post.mock.calls[0][1].body.requestId).toBeTruthy();
  expect(done).toHaveBeenCalled();
});

it("确认已付必须填写凭据并携带版本", async () => {
  vi.clearAllMocks();
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  const refund = {
    id: "refund-1",
    enrollmentId: "enrollment-1",
    studentId: "student-1",
    amount: "200.00",
    status: "PREPARED",
    version: 3,
  };
  renderWithClient(
    <ConfirmPaidModal
      refund={refund as never}
      onCancel={vi.fn()}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByRole("button", { name: /确认已付/ }));
  expect(post).not.toHaveBeenCalled();
  const reference = screen.getByLabelText("支付凭据");
  await user.type(reference, "CASH-2026-0001");
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  await user.click(screen.getByRole("button", { name: /确认已付/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toEqual({
    expectedVersion: 3,
    reference: "CASH-2026-0001",
  });
  expect(done).toHaveBeenCalled();
});

it("余额组件按服务端字符串展示", () => {
  renderWithClient(<BalanceView balance={balance as never} />);
  expect(screen.getByText("总额")).toBeTruthy();
  expect(screen.getByText("冻结")).toBeTruthy();
});
