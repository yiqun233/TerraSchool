import { useState } from "react";
import {
  useQuery,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import { Alert, Button, Form, Input, Table, Typography, Space } from "antd";
import { api, login, logout } from "../../shared/http";
import styles from "./OrganizationPage.module.css";
import { OrganizationEditor } from "./OrganizationEditor";
import { MyPermissions } from "./MyPermissions";
import { ResourceWorkspace } from "./ResourceWorkspace";
import { SchoolEditor } from "./SchoolEditor";
import { StudentDirectory } from "../archive/StudentDirectory";
import { ProductCatalogWorkspace } from "../product/ProductCatalog";
import { EnrollmentWorkspace } from "../enrollment/EnrollmentWorkspace";
import { TeachingWorkspace } from "../teaching/TeachingWorkspace";
import { ApprovalInbox } from "../approval/ApprovalInbox";
import { PeriodManager } from "../finance/PeriodManager";
import type { components } from "../../shared/generated/api";
const labels: Record<string, string> = {
  SCHOOL: "学校",
  CAMPUS: "校区",
  DEPARTMENT: "部门",
};
/** 每次身份变化创建独立缓存；旧请求结果只能回到已卸载的旧缓存。 */
export default function OrganizationPage() {
  const [epoch, setEpoch] = useState(0);
  const [client, setClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchOnWindowFocus: false },
        },
      }),
  );
  const reset = () => {
    void client.cancelQueries();
    client.clear();
    setClient(
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchOnWindowFocus: false },
        },
      }),
    );
    setEpoch((value) => value + 1);
  };
  return (
    <QueryClientProvider key={epoch} client={client}>
      <Workspace reset={reset} />
    </QueryClientProvider>
  );
}
function Workspace({ reset }: { reset: () => void }) {
  const [section, setSection] = useState<
    | "ORGANIZATION"
    | "EMPLOYEE"
    | "TEACHER"
    | "ROOM"
    | "STUDENTS"
    | "PRODUCTS"
    | "ENROLLMENT"
    | "TEACHING"
    | "APPROVAL"
    | "FINANCE"
  >("ORGANIZATION");
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const me = useQuery({
    queryKey: ["session"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/session/permissions", {
        signal,
      });
      if (response.status === 401) return null;
      if (!response.ok) throw new Error("账号当前无组织查询权限");
      return data;
    },
  });
  const units = useQuery({
    queryKey: ["organization", me.data?.accountId, me.data?.tenantId, page],
    enabled: !!me.data?.canReadOrganizations && section === "ORGANIZATION",
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/organization/units", {
        params: { query: { page, size: 20 } },
        signal,
      });
      if (!response.ok)
        throw new Error(
          response.status === 401
            ? "登录已失效，请重新登录"
            : "无法读取组织，请核对当前权限",
        );
      return data;
    },
  });
  async function signIn(values: { username: string; password: string }) {
    setBusy(true);
    setError("");
    try {
      await login(values);
      form.resetFields();
      reset();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function signOut() {
    setBusy(true);
    try {
      await logout();
      setPage(0);
      reset();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className={styles.layout}>
      <aside className={styles.sidebar}>
        <Typography.Title level={3}>TeraSchool</Typography.Title>
        <span>学校运营工作台</span>
        <nav aria-label="功能导航">
          <Space orientation="vertical">
            <Button
              type={section === "ORGANIZATION" ? "primary" : "text"}
              onClick={() => setSection("ORGANIZATION")}
            >
              组织目录
            </Button>
            <Button
              type={section === "EMPLOYEE" ? "primary" : "text"}
              onClick={() => setSection("EMPLOYEE")}
            >
              员工管理
            </Button>
            <Button
              type={section === "TEACHER" ? "primary" : "text"}
              onClick={() => setSection("TEACHER")}
            >
              教师管理
            </Button>
            <Button
              type={section === "ROOM" ? "primary" : "text"}
              onClick={() => setSection("ROOM")}
            >
              教室管理
            </Button>
            <Button
              type={section === "STUDENTS" ? "primary" : "text"}
              onClick={() => setSection("STUDENTS")}
            >
              学员档案
            </Button>
            <Button
              type={section === "PRODUCTS" ? "primary" : "text"}
              onClick={() => setSection("PRODUCTS")}
            >
              课程与产品
            </Button>
            <Button
              type={section === "ENROLLMENT" ? "primary" : "text"}
              onClick={() => setSection("ENROLLMENT")}
            >
              报名与收款
            </Button>
            <Button
              type={section === "TEACHING" ? "primary" : "text"}
              onClick={() => setSection("TEACHING")}
            >
              教务与考勤
            </Button>
            <Button
              type={section === "APPROVAL" ? "primary" : "text"}
              onClick={() => setSection("APPROVAL")}
            >
              审批
            </Button>
            <Button
              type={section === "FINANCE" ? "primary" : "text"}
              onClick={() => setSection("FINANCE")}
            >
              财务期间
            </Button>
          </Space>
        </nav>
      </aside>
      <main className={styles.main}>
        <header className={styles.header}>
          <div>
            <Typography.Text type="secondary">组织与资源</Typography.Text>
            <Typography.Title level={2}>
              {
                {
                  ORGANIZATION: "组织目录",
                  EMPLOYEE: "员工",
                  TEACHER: "教师",
                  ROOM: "教室",
                  STUDENTS: "学员档案",
                  PRODUCTS: "课程与产品",
                  ENROLLMENT: "报名与收款",
                  TEACHING: "教务与考勤",
                  APPROVAL: "审批",
                  FINANCE: "财务期间",
                }[section]
              }
            </Typography.Title>
          </div>
          {me.data ? (
            <Space>
              <MyPermissions />
              <Button onClick={() => void signOut()} loading={busy}>
                退出登录
              </Button>
            </Space>
          ) : null}
        </header>
        {error ? <Alert title={error} type="error" showIcon /> : null}
        {me.isPending ? (
          <p role="status">正在检查登录状态…</p>
        ) : me.error ? (
          <Alert
            title={me.error.message}
            type="error"
            action={
              <Space>
                <MyPermissions />
                <Button onClick={() => void signOut()}>退出登录</Button>
              </Space>
            }
          />
        ) : !me.data ? (
          <section className={styles.login}>
            <Typography.Title level={3}>登录账号</Typography.Title>
            <p>登录后查看已授权学校的学校、校区与部门。</p>
            <Form form={form} layout="vertical" onFinish={signIn}>
              <Form.Item
                name="username"
                label="登录名"
                rules={[{ required: true, message: "请输入登录名" }]}
              >
                <Input autoComplete="username" />
              </Form.Item>
              <Form.Item
                name="password"
                label="密码"
                rules={[{ required: true, message: "请输入密码" }]}
              >
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={busy}>
                登录
              </Button>
            </Form>
          </section>
        ) : section === "STUDENTS" ? (
          <StudentDirectory onUnauthorized={() => void signOut()} />
        ) : section === "PRODUCTS" ? (
          <ProductCatalogWorkspace onUnauthorized={() => void signOut()} />
        ) : section === "ENROLLMENT" ? (
          <EnrollmentWorkspace onUnauthorized={() => void signOut()} />
        ) : section === "TEACHING" ? (
          <TeachingWorkspace onUnauthorized={() => void signOut()} />
        ) : section === "APPROVAL" ? (
          <ApprovalInbox onUnauthorized={() => void signOut()} />
        ) : section === "FINANCE" ? (
          <PeriodManager onUnauthorized={() => void signOut()} />
        ) : section !== "ORGANIZATION" ? (
          <ResourceWorkspace key={section} kind={section} />
        ) : !me.data.canReadOrganizations ? (
          <Alert title="账号当前无组织查询权限" type="error" />
        ) : (
          <>
            <Space className={styles.toolbar}>
              {!units.error ? (
                <OrganizationEditor
                  identityKey={me.data.accountId!}
                  onSaved={() => void units.refetch()}
                />
              ) : null}
              <Typography.Text type="secondary">
                校区与部门分别直属学校
              </Typography.Text>
              <Button
                onClick={() => void units.refetch()}
                loading={units.isFetching}
              >
                刷新
              </Button>
            </Space>
            {units.error ? (
              <Alert
                title={units.error.message}
                type="error"
                action={
                  <Button onClick={() => void signOut()}>退出登录</Button>
                }
              />
            ) : null}
            <Table
              rowKey="id"
              loading={units.isPending}
              dataSource={units.error ? [] : (units.data?.items ?? [])}
              columns={[
                { title: "名称", dataIndex: "name" },
                {
                  title: "类型",
                  dataIndex: "kind",
                  render: (kind: string) => labels[kind] ?? kind,
                },
                { title: "学校标识", dataIndex: "schoolId" },
                {
                  title: "学校状态",
                  render: (
                    _: unknown,
                    unit: components["schemas"]["OrganizationUnit"],
                  ) =>
                    unit.kind === "SCHOOL"
                      ? unit.active
                        ? "启用"
                        : "停用"
                      : "—",
                },
                {
                  title: "操作",
                  key: "actions",
                  render: (
                    _: unknown,
                    unit: components["schemas"]["OrganizationUnit"],
                  ) =>
                    unit.kind === "SCHOOL" &&
                    me.data!.canManageAuthorizations ? (
                      <SchoolEditor
                        school={unit}
                        onSaved={() => void units.refetch()}
                      />
                    ) : (
                      <OrganizationEditor
                        selected={unit}
                        identityKey={me.data!.accountId!}
                        onSaved={() => void units.refetch()}
                      />
                    ),
                },
              ]}
              locale={{ emptyText: "当前范围内暂无组织" }}
              pagination={{
                current: page + 1,
                pageSize: 20,
                total: units.error ? 0 : (units.data?.total ?? 0),
                showSizeChanger: false,
                onChange: (p) => setPage(p - 1),
              }}
            />
          </>
        )}
      </main>
    </div>
  );
}
