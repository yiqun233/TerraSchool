import type { CurrentPermissions } from "./session";

export type NavGroup = "日常" | "招生" | "教务" | "管理" | "财务" | "设置";

export interface NavItem {
  path: string;
  label: string;
  group: NavGroup;
  /** 页面尚未实现时仍保留配置，运行时显示“即将开放”，避免死链 */
  enabled: boolean;
  /** 只有具备对应权限才显示；不把“无权限”伪装成“没数据” */
  visible?: (
    permissions: CurrentPermissions | null,
    tenantAdmin: boolean,
  ) => boolean;
  /** 页面说明用于面包屑与浏览器标题 */
  title: string;
}

const admin = (permissions: CurrentPermissions | null) =>
  !!permissions?.canMaintainOrganizations;

/**
 * 菜单显隐只按“功能是否开启”判断，不猜学校范围：
 * 范围为空时页面自己给四态说明，菜单不因此消失。
 */
const granted = (permissions: CurrentPermissions | null, action: string) =>
  !!permissions?.grantedActions?.includes(action);

/**
 * 租户端导航的唯一配置。
 *
 * 新增页面只改这里与路由表；未实现项 enabled=false，壳层渲染为禁用并标“即将开放”。
 * 不与平台端共用配置（两端权限与入口独立）。
 */
export const TENANT_NAV: NavItem[] = [
  {
    path: "/workbench",
    label: "工作台",
    group: "日常",
    enabled: false,
    title: "工作台",
  },
  {
    path: "/channels",
    label: "招生渠道",
    group: "招生",
    enabled: true,
    title: "招生渠道",
    visible: (permissions) => granted(permissions, "CHANNEL_READ"),
  },
  {
    path: "/customers",
    label: "本人客户",
    group: "招生",
    enabled: true,
    title: "本人客户",
    visible: (permissions) => granted(permissions, "CUSTOMER_READ"),
  },
  {
    path: "/students",
    label: "学员档案",
    group: "招生",
    enabled: true,
    title: "学员档案",
  },
  {
    path: "/products",
    label: "课程与产品",
    group: "教务",
    enabled: true,
    title: "课程与产品",
  },
  {
    path: "/enrollment",
    label: "报名与收款",
    group: "教务",
    enabled: true,
    title: "报名与收款",
  },
  {
    path: "/teaching",
    label: "教务与考勤",
    group: "教务",
    enabled: true,
    title: "教务与考勤",
  },
  {
    path: "/approval",
    label: "审批",
    group: "教务",
    enabled: true,
    title: "审批",
  },
  {
    path: "/organization",
    label: "组织目录",
    group: "管理",
    enabled: true,
    title: "组织目录",
  },
  {
    path: "/employees",
    label: "员工管理",
    group: "管理",
    enabled: true,
    title: "员工",
  },
  {
    path: "/teachers",
    label: "教师管理",
    group: "管理",
    enabled: true,
    title: "教师",
  },
  {
    path: "/rooms",
    label: "教室管理",
    group: "管理",
    enabled: true,
    title: "教室",
  },
  {
    path: "/dictionaries",
    label: "统一字典",
    group: "管理",
    enabled: true,
    title: "统一字典",
    visible: (permissions) => admin(permissions),
  },
  {
    path: "/finance",
    label: "财务期间",
    group: "财务",
    enabled: true,
    title: "财务期间",
  },
  {
    path: "/reports",
    label: "报表",
    group: "财务",
    enabled: false,
    title: "报表",
  },
  {
    path: "/permissions",
    label: "权限管理",
    group: "设置",
    enabled: true,
    title: "权限管理",
  },
  {
    path: "/settings/branding",
    label: "品牌与外观",
    group: "设置",
    enabled: true,
    title: "品牌与外观",
    visible: (permissions, tenantAdmin) =>
      tenantAdmin || !!permissions?.canWriteBranding,
  },
  {
    path: "/migration",
    label: "数据迁移",
    group: "设置",
    enabled: true,
    title: "数据迁移",
  },
];

export const TENANT_GROUPS: NavGroup[] = [
  "日常",
  "招生",
  "教务",
  "管理",
  "财务",
  "设置",
];

export function findNav(pathname: string): NavItem | undefined {
  return TENANT_NAV.find(
    (item) => pathname === item.path || pathname.startsWith(`${item.path}/`),
  );
}

export function visibleNavigation(
  permissions: CurrentPermissions | null,
  tenantAdmin: boolean,
) {
  return TENANT_NAV.filter(
    (item) => !item.visible || item.visible(permissions, tenantAdmin),
  );
}
