import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PeriodActionModal, PeriodManager } from "./PeriodManager";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

const capabilities = {
  canRead: true,
  canLock: true,
  canUnlock: true,
  schools: [
    { id: "school-1", name: "期间学校", canLock: true, canUnlock: true },
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

it("列出十二个月并按状态标注，锁定月份显示解锁按钮", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/finance/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/finance/periods")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            schoolId: "school-1",
            period: "2026-01",
            status: "LOCKED",
            reason: "月末结账",
            version: 1,
          },
          {
            schoolId: "school-1",
            period: "2026-02",
            status: "OPEN",
            version: 0,
          },
        ],
      };
    if (path === "/api/finance/reports/funds-reconciliation")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/finance/reports/receipt-daily")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/finance/reports/daily-overview")
      return { response: { ok: false, status: 403 } };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<PeriodManager onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("2026-01")).toBeTruthy();
  expect(screen.getByText("已锁定")).toBeTruthy();
  expect(screen.getByText("开放")).toBeTruthy();
  expect(screen.getByRole("button", { name: /解\s*锁/ })).toBeTruthy();
  expect(screen.getByRole("button", { name: /锁\s*定/ })).toBeTruthy();
  expect(screen.getByText(/已锁定 1 个月/)).toBeTruthy();
});

it("对账卡片显示累计平衡式分项和差额", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/finance/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/finance/periods")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/finance/reports/funds-reconciliation")
      return {
        response: { ok: true, status: 200 },
        data: {
          schoolId: "school-1",
          openingAmount: "100.00",
          netReceiptAmount: "1000.00",
          cashRefundAmount: "50.00",
          activeAllocatedAmount: "300.00",
          accountBalanceAmount: "750.00",
          differenceAmount: "0.00",
        },
      };
    if (path === "/api/finance/reports/receipt-daily")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/finance/reports/daily-overview")
      return { response: { ok: false, status: 403 } };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<PeriodManager onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("资金总账对账（累计）")).toBeTruthy();
  expect(screen.getByText("1000.00")).toBeTruthy();
  expect(screen.getByText("0.00")).toBeTruthy();
  expect(screen.getByText(/不按所选年份或财务期间筛选/)).toBeTruthy();
});

it("展示按日收款原额并把撤销单据单独列示", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/finance/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/finance/periods")
      return { response: { ok: true, status: 200 }, data: [] };
    if (path === "/api/finance/reports/funds-reconciliation")
      return { response: { ok: false, status: 403 } };
    if (path === "/api/finance/reports/receipt-daily")
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            day: "2026-04-12",
            method: "CASH",
            status: "CONFIRMED",
            receiptCount: 2,
            grossAmount: "150.00",
          },
          {
            day: "2026-04-13",
            method: "BANK",
            status: "VOIDED",
            receiptCount: 1,
            grossAmount: "40.00",
          },
        ],
      };
    if (path === "/api/finance/reports/daily-overview")
      return {
        response: { ok: true, status: 200 },
        data: {
          day: "2026-04-12",
          enrollmentCount: 2,
          confirmedReceiptCount: 1,
          confirmedReceiptAmount: "150.00",
          voidedReceiptCount: 1,
          voidedReceiptAmount: "40.00",
          lessonCount: 3,
          closedLessonCount: 2,
          attendanceRecordCount: 18,
        },
      };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<PeriodManager onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("按日收款统计（单据原额）")).toBeTruthy();
  expect(await screen.findByText("2026-04-12")).toBeTruthy();
  expect(screen.getByText("2026-04-13")).toBeTruthy();
  expect(screen.getByText("已撤销")).toBeTruthy();
  expect(screen.getAllByText("150.00")).toHaveLength(2);
  expect(await screen.findByText("每日运营概况（候选口径）")).toBeTruthy();
  expect(screen.getByText("18")).toBeTruthy();
  expect(screen.getByText(/不含现金退款净额或财务确收口径/)).toBeTruthy();
  expect(get).toHaveBeenCalledWith(
    "/api/finance/reports/receipt-daily",
    expect.objectContaining({
      params: expect.objectContaining({
        query: expect.objectContaining({ schoolId: "school-1" }),
      }),
    }),
  );
});

it("解锁必须填写原因并携带版本", async () => {
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <PeriodActionModal
      schoolId="school-1"
      period={
        {
          schoolId: "school-1",
          period: "2026-01",
          status: "LOCKED",
          version: 7,
        } as never
      }
      lock={false}
      onCancel={vi.fn()}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByRole("button", { name: /确认解锁/ }));
  expect(post).not.toHaveBeenCalled();
  await user.type(screen.getByLabelText("原因"), "更正上月末的对账");
  await user.click(screen.getByRole("button", { name: /确认解锁/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe("/api/finance/periods/{period}/unlock");
  expect(post.mock.calls[0][1].params.path.period).toBe("2026-01");
  expect(post.mock.calls[0][1].body).toMatchObject({
    schoolId: "school-1",
    period: "2026-01",
    reason: "更正上月末的对账",
    expectedVersion: 7,
  });
  expect(done).toHaveBeenCalled();
});
