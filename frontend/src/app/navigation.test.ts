import { expect, it } from "vitest";
import { visibleNavigation } from "./navigation";
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
