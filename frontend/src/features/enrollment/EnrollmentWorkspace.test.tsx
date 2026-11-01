import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  AllocateAction,
  ApproveButton,
  EnrollmentWorkspace,
  VoidReceiptButton,
  validateCourseLines,
} from "./EnrollmentWorkspace";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

const capabilities = {
  canRead: true,
  canMaintain: true,
  canApprove: true,
  schools: [{ id: "school-1", name: "测试学校", maintainable: true }],
};

const enrollment = {
  id: "enrollment-1",
  schoolId: "school-1",
  studentId: "student-1",
  studentName: "学员甲",
  productName: "少儿英语年卡",
  curriculumName: "少儿英语",
  price: "1000.00",
  hours: "10.00",
  serviceFee: "0.00",
  settlementMode: 1,
  status: "PENDING_AUDIT",
  netAmount: "1000.00",
  version: 0,
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

it("报名列表展示冻结价格与净应收字符串", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/enrollment/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/enrollment/enrollments")
      return {
        response: { ok: true, status: 200 },
        data: { items: [enrollment], page: 0, size: 20, total: 1 },
      };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<EnrollmentWorkspace onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("少儿英语年卡")).toBeTruthy();
  expect(screen.getByText("1000.00 元 / 10.00 课时")).toBeTruthy();
  expect(screen.getByText("1000.00")).toBeTruthy();
});

it("审核携带读取时的版本", async () => {
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const done = vi.fn();
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <ApproveButton
      enrollment={enrollment as never}
      onDone={done}
      onError={vi.fn()}
    />,
  );
  await user.click(screen.getByRole("button", { name: /审\s*核/ }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/enrollment/enrollments/{id}/approve",
  );
  expect(post.mock.calls[0][1].body).toEqual({ expectedVersion: 0 });
  expect(post.mock.calls[0][1].params.path.id).toBe("enrollment-1");
  expect(done).toHaveBeenCalled();
});

it(
  "分配被拒时提示不覆盖权威金额，撤销收款携带版本",
  { timeout: 15000 },
  async () => {
    csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
    post.mockResolvedValue({ response: { ok: false, status: 409 } });
    const onError = vi.fn();
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithClient(
      <AllocateAction
        enrollmentId="enrollment-1"
        outstanding="1000.00"
        receipts={[
          {
            id: "receipt-1",
            schoolId: "school-1",
            studentId: "student-1",
            amount: "500.00",
            allocatedAmount: "0.00",
            availableAmount: "500.00",
            method: "CASH",
            occurredOn: "2026-02-10",
            status: "CONFIRMED",
            version: 0,
          } as never,
        ]}
        onDone={vi.fn()}
        onError={onError}
      />,
    );
    await user.click(screen.getByRole("button", { name: /分配收款/ }));
    await user.click(screen.getByRole("combobox", { name: "收款记录" }));
    await user.click(await screen.findByText(/可用 500.00 元/));
    const amount = screen.getByRole("spinbutton", { name: "分配金额（元）" });
    await user.type(amount, "600");
    await user.click(screen.getByRole("button", { name: /确认分配/ }));
    await waitFor(() =>
      expect(onError).toHaveBeenCalledWith(expect.stringContaining("超出可用")),
    );

    renderWithClient(
      <VoidReceiptButton
        receipt={
          {
            id: "receipt-1",
            amount: "500.00",
            status: "CONFIRMED",
            version: 3,
            availableAmount: "500.00",
            occurredOn: "2026-02-10",
            method: "CASH",
          } as never
        }
        onDone={vi.fn()}
        onError={vi.fn()}
      />,
    );
    await user.click(screen.getAllByRole("button", { name: /撤\s*销/ })[0]);
    await waitFor(() =>
      expect(
        post.mock.calls.some(
          (call) => call[0] === "/api/funds/receipts/{id}/void",
        ),
      ).toBe(true),
    );
    const voidCall = post.mock.calls.find(
      (call) => call[0] === "/api/funds/receipts/{id}/void",
    );
    expect(voidCall![1].body).toEqual({ expectedVersion: 3 });
  },
);

it("课程明细合计校验：课时与费用必须等于价格版本", () => {
  const price = { hours: "4.00", price: "2000.00" };
  // 语文 1 课时 200 + 数学 3 课时 1800 = 4 课时 2000 元。
  expect(
    validateCourseLines(
      [
        { curriculumId: "c-1", hours: "1", amount: "200" },
        { curriculumId: "c-2", hours: "3", amount: "1800" },
      ],
      price,
    ),
  ).toBeNull();
  expect(
    validateCourseLines(
      [{ curriculumId: "c-1", hours: "1", amount: "200" }],
      price,
    ),
  ).toContain("课时合计");
  expect(
    validateCourseLines(
      [
        { curriculumId: "c-1", hours: "1", amount: "200" },
        { curriculumId: "c-1", hours: "3", amount: "1800" },
      ],
      price,
    ),
  ).toBe("同一课程不能重复出现");
  expect(validateCourseLines([{ hours: "4", amount: "2000" }], price)).toBe(
    "每行明细都要选择课程",
  );
});
