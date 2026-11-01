import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ResourceDetails } from "./ResourceDetails";
const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
it("教师资质使用读取版本提交并在权限拒绝后阻止覆盖", async () => {
  get.mockResolvedValue({
    response: { ok: true },
    data: {
      teacherId: "t",
      schoolId: "s",
      qualification: "原资质",
      version: 4,
    },
  });
  post.mockResolvedValue({ response: { ok: false, status: 403 } });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "token" });
  const user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ResourceDetails
        row={{
          id: "t",
          schoolId: "s",
          kind: "TEACHER",
          name: "教师",
          active: true,
          version: 4,
        }}
        mode="qualification"
        onSaved={vi.fn()}
      />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "教师资质" }));
  const field = await screen.findByLabelText("资质简介");
  await user.clear(field);
  await user.type(field, "新资质");
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await screen.findByText("当前权限不足或学校已停用。");
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body).toEqual({
    expectedVersion: 4,
    qualification: "新资质",
  });
});

it("通讯资料使用独立接口和读取版本保存", async () => {
  vi.clearAllMocks();
  get.mockResolvedValue({
    response: { ok: true },
    data: {
      employeeId: "e",
      schoolId: "s",
      version: 9,
      data: { workWechatUserId: "old-id" },
    },
  });
  post.mockResolvedValue({ response: { ok: true, status: 200 } });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "token" });
  const saved = vi.fn(),
    user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ResourceDetails
        row={{
          id: "e",
          schoolId: "s",
          kind: "EMPLOYEE",
          name: "员工",
          active: true,
          version: 9,
        }}
        mode="external"
        onSaved={saved}
      />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "通讯资料" }));
  const field = await screen.findByLabelText("企业微信用户 ID");
  await user.clear(field);
  await user.type(field, "new-id");
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/organization/employees/{id}/external-identifiers",
  );
  expect(post.mock.calls[0][1].body.expectedVersion).toBe(9);
  expect(post.mock.calls[0][1].body.data.workWechatUserId).toBe("new-id");
}, 10000);
