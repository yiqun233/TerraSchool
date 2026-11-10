import { lazy } from "react";
import { createBrowserRouter, Navigate, useRouteError } from "react-router-dom";
import { Button } from "antd";
import { AppShell } from "./AppShell";
import { PendingAccessPage } from "./PendingAccessPage";
import { startPath } from "./navigation";
import { useSession } from "./session";
import {
  LoadingBlock,
  Page,
  PageHeader,
  Panel,
  StateBlock,
} from "../shared/ui/Page";

/**
 * 业务页按路由加载：首屏只带壳层、登录态与当前页，避免一次加载全部工作台。
 * 页面仍是命名导出，用 lazy 工厂包装成路由组件。
 */
function withUnauthorized<T extends object>(
  Component: React.ComponentType<T & { onUnauthorized: () => void }>,
) {
  return function AuthorizedPage(props: T) {
    const { onUnauthorized } = useSession();
    return <Component {...props} onUnauthorized={onUnauthorized} />;
  };
}

const OrganizationPage = lazy(async () => {
  const { OrganizationDirectory } =
    await import("../features/organization/OrganizationDirectory");
  return { default: OrganizationDirectory };
});

const StudentsPage = lazy(async () => {
  const { StudentDirectory } =
    await import("../features/archive/StudentDirectory");
  return { default: withUnauthorized(StudentDirectory) };
});

const ProductsPage = lazy(async () => {
  const { ProductCatalogWorkspace } =
    await import("../features/product/ProductCatalog");
  return { default: withUnauthorized(ProductCatalogWorkspace) };
});

const EnrollmentPage = lazy(async () => {
  const { EnrollmentWorkspace } =
    await import("../features/enrollment/EnrollmentWorkspace");
  return { default: withUnauthorized(EnrollmentWorkspace) };
});

const TeachingPage = lazy(async () => {
  const { TeachingWorkspace } =
    await import("../features/teaching/TeachingWorkspace");
  return { default: withUnauthorized(TeachingWorkspace) };
});

const ApprovalPage = lazy(async () => {
  const { ApprovalInbox } = await import("../features/approval/ApprovalInbox");
  return { default: withUnauthorized(ApprovalInbox) };
});

const FinancePage = lazy(async () => {
  const { PeriodManager } = await import("../features/finance/PeriodManager");
  return { default: withUnauthorized(PeriodManager) };
});

const MigrationPage = lazy(async () => {
  const { MigrationConsole } =
    await import("../features/migration/MigrationConsole");
  return { default: withUnauthorized(MigrationConsole) };
});

const ChannelsPage = lazy(async () => {
  const { ChannelWorkspace } =
    await import("../features/admissions/ChannelWorkspace");
  return {
    default: function ChannelsPage() {
      const { session, onUnauthorized } = useSession();
      return (
        <ChannelWorkspace
          accountKey={`${session.tenantId}:${session.accountId}`}
          onUnauthorized={onUnauthorized}
        />
      );
    },
  };
});

const CustomersPage = lazy(async () => {
  const { CustomerWorkspace } =
    await import("../features/admissions/CustomerWorkspace");
  return {
    default: function CustomersPage() {
      const { session, onUnauthorized } = useSession();
      return (
        <CustomerWorkspace
          accountKey={`${session.tenantId}:${session.accountId}`}
          onUnauthorized={onUnauthorized}
        />
      );
    },
  };
});

const DictionariesPage = lazy(async () => {
  const { DictionaryWorkspace } =
    await import("../features/organization/DictionaryWorkspace");
  return {
    default: function DictionariesPage() {
      const { session } = useSession();
      return (
        <DictionaryWorkspace
          identityKey={`${session.tenantId}:${session.accountId}`}
        />
      );
    },
  };
});

function resourcePage(kind: "EMPLOYEE" | "TEACHER" | "ROOM") {
  return lazy(async () => {
    const { ResourceWorkspace } =
      await import("../features/organization/ResourceWorkspace");
    return {
      default: function ResourcePage() {
        return <ResourceWorkspace kind={kind} />;
      },
    };
  });
}

const EmployeesPage = resourcePage("EMPLOYEE");
const TeachersPage = resourcePage("TEACHER");
const RoomsPage = resourcePage("ROOM");

const BrandingPage = lazy(async () => {
  const { BrandingSettings } =
    await import("../features/settings/BrandingSettings");
  return { default: BrandingSettings };
});

const PermissionsPage = lazy(async () => {
  const { PermissionConsole } =
    await import("../features/identity/PermissionConsole");
  return { default: PermissionConsole };
});

/**
 * 默认落点：按“这个账号真能打开哪个页面”决定，不拿可读学校当业务能力的替身。
 * 有学校范围只代表能看组织目录，不代表能看学员档案；什么都没有时去 /start 说明，
 * 而不是落进一个必然 403 的业务页。
 */
function StartPage() {
  const { permissions, permissionsLoading } = useSession();
  if (permissionsLoading) return <LoadingBlock>正在确认可用范围…</LoadingBlock>;
  return <Navigate to={startPath(permissions)} replace />;
}

function NotFound() {
  return (
    <Page>
      <PageHeader
        title="页面不存在"
        description="地址可能已变更，或该功能尚未开放。"
      />
      <Panel>
        <StateBlock
          title="没有找到这个页面"
          hint="请从左侧导航进入已有功能；未实现的功能会标记“即将开放”。"
          action={
            <Button type="primary" href="/organization">
              回到组织目录
            </Button>
          }
        />
      </Panel>
    </Page>
  );
}

function RouteError() {
  const error = useRouteError();
  return (
    <Page>
      <PageHeader title="页面加载失败" />
      <Panel>
        <StateBlock
          title="页面没有正常显示"
          hint={
            error instanceof Error
              ? error.message
              : "请刷新页面；如果仍然失败，请联系机构管理员。"
          }
          action={
            <Button type="primary" onClick={() => window.location.reload()}>
              刷新页面
            </Button>
          }
        />
      </Panel>
    </Page>
  );
}

/**
 * 租户端路由表：路径只负责组合页面，授权仍由接口逐次校验。
 *
 * `/login` 与 `/change-password` 在已登录时回到首页，避免身份状态变化后停在
 * 一个不属于当前壳层的地址上。
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <StartPage /> },
      { path: "start", element: <PendingAccessPage /> },
      { path: "organization", element: <OrganizationPage /> },
      { path: "employees", element: <EmployeesPage /> },
      { path: "teachers", element: <TeachersPage /> },
      { path: "rooms", element: <RoomsPage /> },
      { path: "channels", element: <ChannelsPage /> },
      { path: "customers", element: <CustomersPage /> },
      { path: "students", element: <StudentsPage /> },
      { path: "products", element: <ProductsPage /> },
      { path: "enrollment", element: <EnrollmentPage /> },
      { path: "teaching", element: <TeachingPage /> },
      { path: "approval", element: <ApprovalPage /> },
      { path: "finance", element: <FinancePage /> },
      { path: "migration", element: <MigrationPage /> },
      { path: "dictionaries", element: <DictionariesPage /> },
      { path: "permissions", element: <PermissionsPage /> },
      { path: "settings/branding", element: <BrandingPage /> },
      { path: "login", element: <Navigate to="/" replace /> },
      { path: "change-password", element: <Navigate to="/" replace /> },
      { path: "*", element: <NotFound /> },
    ],
  },
]);
