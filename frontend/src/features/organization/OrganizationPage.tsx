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
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const me = useQuery({
    queryKey: ["session"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/session/me", { signal });
      if (response.status === 401) return null;
      if (!response.ok) throw new Error("账号当前无组织查询权限");
      return data;
    },
  });
  const units = useQuery({
    queryKey: ["organization", me.data?.accountId, me.data?.tenantId, page],
    enabled: !!me.data,
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
        <nav aria-label="功能导航">组织与资源</nav>
      </aside>
      <main className={styles.main}>
        <header className={styles.header}>
          <div>
            <Typography.Text type="secondary">组织与资源</Typography.Text>
            <Typography.Title level={2}>组织目录</Typography.Title>
          </div>
          {me.data ? (
            <Button onClick={() => void signOut()} loading={busy}>
              退出登录
            </Button>
          ) : null}
        </header>
        {error ? <Alert title={error} type="error" showIcon /> : null}
        {me.isPending ? (
          <p role="status">正在检查登录状态…</p>
        ) : me.error ? (
          <Alert
            title={me.error.message}
            type="error"
            action={<Button onClick={() => void signOut()}>退出登录</Button>}
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
                  title: "操作",
                  key: "actions",
                  render: (
                    _: unknown,
                    unit: components["schemas"]["OrganizationUnit"],
                  ) => (
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
