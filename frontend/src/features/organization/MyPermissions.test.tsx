import { it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MyPermissions } from "./MyPermissions";
const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("../../shared/http", () => ({ api: { GET: get } }));
it("按需读取本人权限，重开刷新且读取失败时隐藏旧结果", async () => {
  const user = userEvent.setup();
  get
    .mockResolvedValueOnce({
      response: { ok: true },
      data: {
        canReadOrganizations: true,
        canMaintainOrganizations: true,
        readableSchoolIds: ["a", "b"],
        maintainableSchoolIds: ["a"],
      },
    })
    .mockRejectedValueOnce(new Error("权限读取失败"))
    .mockResolvedValueOnce({
      response: { ok: true },
      data: {
        canReadOrganizations: false,
        canMaintainOrganizations: false,
        readableSchoolIds: [],
        maintainableSchoolIds: [],
      },
    });
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MyPermissions />
    </QueryClientProvider>,
  );
  expect(get).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "我的权限" }));
  await screen.findByText("可查看学校：2所");
  await screen.findByText("可维护学校：1所");
  await user.click(screen.getByRole("button", { name: /关\s*闭/ }));
  await user.click(screen.getByRole("button", { name: "我的权限" }));
  await screen.findByText("权限读取失败");
  await waitFor(() => expect(screen.queryByText("可查看学校：2所")).toBeNull());
  await user.click(screen.getByRole("button", { name: /关\s*闭/ }));
  await user.click(screen.getByRole("button", { name: "我的权限" }));
  await screen.findByText("组织查看：未允许");
  await screen.findByText("可查看学校：0所");
  expect(get).toHaveBeenCalledTimes(3);
});

it("关闭授权管理后清空旧会话和组织缓存并重新核对本人权限", async () => {
  get.mockReset();
  get.mockImplementation(async (path: string) => ({
    response: { ok: true },
    data:
      path === "/api/session/permissions"
        ? {
            canReadOrganizations: false,
            canMaintainOrganizations: false,
            canManageAuthorizations: true,
            readableSchoolIds: [],
            maintainableSchoolIds: [],
            manageableSchoolIds: ["a"],
          }
        : [],
  }));
  const user = userEvent.setup();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  client.setQueryData(["session"], { accountId: "stale" });
  client.setQueryData(["organization", "stale", 0], {
    items: ["previously-visible"],
  });
  render(
    <QueryClientProvider client={client}>
      <MyPermissions />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "我的权限" }));
  await user.click(await screen.findByRole("button", { name: "管理账号授权" }));
  await user.click(await screen.findByRole("button", { name: "关闭管理" }));
  await waitFor(() => expect(client.getQueryData(["session"])).toBeUndefined());
  expect(client.getQueryData(["organization", "stale", 0])).toBeUndefined();
  await waitFor(() =>
    expect(
      get.mock.calls.filter((call) => call[0] === "/api/session/permissions"),
    ).toHaveLength(2),
  );
});
