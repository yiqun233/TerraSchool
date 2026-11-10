import { expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { components } from "../../shared/generated/api";
import { PermissionConsole } from "./PermissionConsole";

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("../../shared/http", () => ({ api: { GET: get } }));

const current: components["schemas"]["CurrentPermissions"] = {
  accountId: "account",
  tenantId: "tenant",
  canReadOrganizations: true,
  canMaintainOrganizations: true,
  readableSchoolIds: ["school-a"],
  maintainableSchoolIds: ["school-a"],
  canManageAuthorizations: false,
  manageableSchoolIds: [],
  tenantAdmin: false,
  passwordChangeRequired: false,
  canWriteBranding: false,
  grantedActions: ["ORGANIZATION_READ", "ORGANIZATION_MAINTAIN"],
};

let session = {
  permissions: current,
  permissionsLoading: false,
  onUnauthorized: vi.fn(),
  reload: vi.fn(),
};
vi.mock("../../app/session", () => ({ useSession: () => session }));

const catalog: components["schemas"]["Action"][] = [
  {
    code: "ORGANIZATION_READ",
    group: "组织与身份",
    label: "组织查看",
    description: "查看本机构的学校、校区与部门目录",
    pages: ["组织目录"],
    scope: "SCHOOL",
    risk: "NORMAL",
  },
  {
    code: "ORGANIZATION_MAINTAIN",
    group: "组织与身份",
    label: "组织维护",
    description: "新增学校、校区、部门并改名",
    pages: ["组织目录"],
    scope: "SCHOOL",
    risk: "NORMAL",
  },
  {
    code: "AUTHORIZATION_MANAGE",
    group: "组织与身份",
    label: "授权管理",
    description: "管理账号授权、角色模板与账号生命周期",
    pages: ["权限管理"],
    scope: "SCHOOL",
    risk: "SENSITIVE",
  },
];

function mockApi() {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/permissions/catalog")
      return { response: { ok: true }, data: catalog };
    if (path === "/api/permissions/mine")
      return {
        response: { ok: true },
        data: {
          current,
          actions: [
            {
              action: "ORGANIZATION_READ",
              functionalOnly: false,
              schoolIds: ["school-a"],
            },
            // 只有功能级允许：不能显示成“有数据权限”。
            {
              action: "ORGANIZATION_MAINTAIN",
              functionalOnly: true,
              schoolIds: [],
            },
          ],
        },
      };
    if (path === "/api/organization/school-options")
      return {
        response: { ok: true },
        data: [{ id: "school-a", name: "演示学校（总部）" }],
      };
    if (path === "/api/organization/schools/capabilities")
      return { response: { ok: true }, data: { canCreate: true } };
    throw new Error("unexpected " + path);
  });
}

function view() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <PermissionConsole />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

it("按业务分组呈现本人权限，区分学校范围、仅功能与未开通", async () => {
  mockApi();
  session = { ...session, permissions: current, permissionsLoading: false };
  view();
  await screen.findByText("已开通 2 / 3 项");
  expect(screen.getByText("组织与身份")).toBeTruthy();
  expect(screen.getByText("1 所学校")).toBeTruthy();
  // 功能级允许不产生学校范围，必须显式说明。
  expect(screen.getByText("仅功能（无学校范围）")).toBeTruthy();
  expect(screen.getByText("未开通")).toBeTruthy();
  expect(screen.getByText("学校：演示学校（总部）")).toBeTruthy();
});

it("动作目录与账号管理入口分别呈现，无管理权限时给出说明", async () => {
  mockApi();
  session = { ...session, permissions: current, permissionsLoading: false };
  const user = userEvent.setup();
  view();
  await user.click(screen.getByRole("tab", { name: "账号与角色" }));
  await screen.findByText("当前账号不能管理授权");
  expect(screen.queryByRole("button", { name: "管理账号授权" })).toBeNull();
  await user.click(screen.getByRole("tab", { name: "动作目录" }));
  await screen.findByText(/共 3 个动作/);
  expect(screen.getAllByText("ORGANIZATION_READ").length).toBe(1);
});

it("有管理权限时给出三个管理入口，开通学校归入组织目录", async () => {
  get.mockReset();
  mockApi();
  session = {
    ...session,
    permissions: {
      ...current,
      canManageAuthorizations: true,
      manageableSchoolIds: ["school-a"],
    } as components["schemas"]["CurrentPermissions"],
    permissionsLoading: false,
  };
  const user = userEvent.setup();
  view();
  await user.click(screen.getByRole("tab", { name: "账号与角色" }));
  await screen.findByText(/可管理授权的学校：1 所/);
  for (const name of ["管理账号授权", "维护角色模板", "管理账号"]) {
    expect(await screen.findByRole("button", { name })).toBeTruthy();
  }
  // 开通学校属于组织动作，只在组织目录提供，避免两个入口互相矛盾。
  expect(screen.queryByRole("button", { name: "开通学校" })).toBeNull();
});

it("还没有学校时说明先开通学校，并给出组织目录入口", async () => {
  get.mockReset();
  mockApi();
  session = {
    ...session,
    permissions: {
      ...current,
      canManageAuthorizations: true,
      manageableSchoolIds: [],
    } as components["schemas"]["CurrentPermissions"],
    permissionsLoading: false,
  };
  const user = userEvent.setup();
  view();
  await user.click(screen.getByRole("tab", { name: "账号与角色" }));
  await screen.findByText("还没有学校：先到“组织目录”开通第一所");
  expect(screen.getByRole("button", { name: "去组织目录" })).toBeTruthy();
});

it("读取失败时说明原因，不把空列表当成没有权限", async () => {
  get.mockReset();
  get.mockRejectedValue(new Error("网络错误"));
  session = { ...session, permissions: current, permissionsLoading: false };
  view();
  await screen.findByText("无法读取当前权限");
});
