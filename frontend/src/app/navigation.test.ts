import { expect, it } from "vitest";
import { startPath, visibleNavigation } from "./navigation";
import type { CurrentPermissions } from "./session";

function permissions(grantedActions: string[]): CurrentPermissions {
  return {
    accountId: "account",
    tenantId: "tenant",
    canReadOrganizations: true,
    canMaintainOrganizations: true,
    readableSchoolIds: ["school"],
    maintainableSchoolIds: ["school"],
    canManageAuthorizations: false,
    manageableSchoolIds: [],
    tenantAdmin: false,
    passwordChangeRequired: false,
    canWriteBranding: false,
    grantedActions,
  };
}

it("菜单只显示功能已开启的入口，不把无权限页面伪装成可用", () => {
  const paths = visibleNavigation(permissions(["CUSTOMER_READ"]), false).map(
    (item) => item.path,
  );
  expect(paths).toContain("/customers");
  // 没有 CHANNEL_READ 时不显示招生渠道，避免点进去直接 403。
  expect(paths).not.toContain("/channels");
  expect(paths).toContain("/permissions");
});

it("租户管理员与品牌写入分别控制品牌入口", () => {
  const viewer = visibleNavigation(permissions([]), false).map(
    (item) => item.path,
  );
  expect(viewer).not.toContain("/settings/branding");
  const admin = visibleNavigation(permissions([]), true).map(
    (item) => item.path,
  );
  expect(admin).toContain("/settings/branding");
});

it("业务菜单按动作显隐，教务与财务在没权限时不再出现", () => {
  const none = visibleNavigation(permissions([]), false).map(
    (item) => item.path,
  );
  for (const path of [
    "/students",
    "/products",
    "/enrollment",
    "/teaching",
    "/approval",
    "/employees",
    "/teachers",
    "/rooms",
    "/finance",
    "/migration",
  ]) {
    expect(none, `${path} 不应在没有动作时显示`).not.toContain(path);
  }
  // 管理入口保留：权限管理用于自查，组织目录用于建第一所学校。
  expect(none).toContain("/permissions");
  expect(none).toContain("/organization");

  const granted = visibleNavigation(
    permissions(["TEACHING_READ", "PERIOD_READ"]),
    false,
  ).map((item) => item.path);
  expect(granted).toContain("/teaching");
  expect(granted).toContain("/finance");
  expect(granted).not.toContain("/students");
});

it("登录落点按能力选择，没有业务权限时去说明页", () => {
  expect(startPath(permissions(["STUDENT_READ"]))).toBe("/students");
  // 只有组织权限（例如刚建完学校的机构管理员）时先去组织目录，不落到学员档案。
  expect(startPath(permissions([]))).toBe("/organization");
  // 租户管理员即使已开通全部动作，没有学校时也先去建校。
  expect(
    startPath({
      ...permissions(["STUDENT_READ"]),
      tenantAdmin: true,
      readableSchoolIds: [],
    }),
  ).toBe("/organization");
  // 有了学校就可以落到学员档案。
  expect(
    startPath({ ...permissions(["STUDENT_READ"]), tenantAdmin: true }),
  ).toBe("/students");
  expect(startPath({ ...permissions([]), canReadOrganizations: false })).toBe(
    "/start",
  );
  expect(startPath(null)).toBe("/start");
});
