import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
type View = components["schemas"]["AccountView"];
type Create = components["schemas"]["CreateAccount"];

export function AccountLifecycleManager({
  onClosed,
}: {
  onClosed: () => void;
}) {
  const [open, setOpen] = useState(false),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState<string>();
  const [creating, setCreating] = useState(false),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const list = useQuery({
    queryKey: ["lifecycle-options", search],
    enabled: open,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/authorization/accounts", {
        signal,
        params: { query: { search } },
      });
      if (!response.ok || !data) throw new Error("无法读取可管理账号");
      return data;
    },
  });
  const account = useQuery({
    queryKey: ["account-lifecycle", selected],
    enabled: open && !!selected,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/authorization/account-lifecycle/{id}",
        { signal, params: { path: { id: selected! } } },
      );
      if (!response.ok || !data || data.version == null)
        throw new Error("无法读取账号，请检查管理范围");
      return data;
    },
  });
  function close() {
    if (!busy) {
      setOpen(false);
      setSelected(undefined);
      setCreating(false);
      onClosed();
    }
  }
  function saved() {
    setNotice("账号操作已完成，请重新选择账号核对当前状态。");
    setSelected(undefined);
    setCreating(false);
    void list.refetch();
  }
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true);
          setNotice("");
        }}
      >
        管理账号
      </Button>
      <Modal
        title="账号管理"
        open={open}
        width={720}
        onCancel={close}
        mask={{ closable: !busy }}
        closable={!busy}
        keyboard={!busy}
        footer={
          <Button disabled={busy} onClick={close}>
            关闭账号管理
          </Button>
        }
      >
        <p>
          账号与员工档案各自维护。停用、密码重置或撤销会话后，已有登录需要重新认证。
        </p>
        <Space wrap>
          <Select
            aria-label="选择管理账号"
            placeholder="按登录名搜索"
            style={{ width: 360 }}
            value={selected}
            disabled={busy}
            showSearch={{ filterOption: false, onSearch: setSearch }}
            options={
              list.error || list.isFetching
                ? []
                : (list.data ?? []).map((item) => ({
                    value: item.id!,
                    label: item.name!,
                  }))
            }
            onChange={(id) => {
              setSelected(id);
              setCreating(false);
              setNotice("");
            }}
          />
          <Button
            disabled={busy}
            onClick={() => {
              setSelected(undefined);
              setCreating(true);
              setNotice("");
            }}
          >
            创建账号
          </Button>
        </Space>
        {notice ? <Alert title={notice} type="success" /> : null}
        {list.error || account.error ? (
          <Alert title={(list.error || account.error)?.message} type="error" />
        ) : null}
        {account.isFetching && selected ? (
          <p role="status">正在读取账号状态…</p>
        ) : null}
        {creating ? (
          <AccountCreateForm key="create" onBusy={setBusy} onSaved={saved} />
        ) : selected &&
          account.data &&
          !account.isFetching &&
          !account.error ? (
          <AccountEditForm
            key={`${selected}:${account.data.version}`}
            account={account.data}
            onBusy={setBusy}
            onSaved={saved}
          />
        ) : null}
      </Modal>
    </>
  );
}
export function AccountCreateForm({
  onBusy,
  onSaved,
}: {
  onBusy: (value: boolean) => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<Create>();
  const [busy, setBusy] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const inFlight = useRef(false);
  async function save(values: Create) {
    if (inFlight.current || blocked) return;
    inFlight.current = true;
    setBusy(true);
    onBusy(true);
    setError("");
    let sent = false;
    try {
      const token = await csrf();
      sent = true;
      const { response, error: problem } = await api.POST(
        "/api/authorization/account-lifecycle",
        { headers: { [token.headerName!]: token.token! }, body: values },
      );
      form.resetFields(["password"]);
      if (!response.ok) {
        const code = (problem as { code?: string } | undefined)?.code;
        if (response.status >= 500 || response.status === 408) {
          setBlocked(true);
          setError("创建结果尚未确认，请关闭后按登录名检索核对。");
        } else
          setError(
            code === "ACCOUNT_LOGIN_UNAVAILABLE"
              ? "该登录名不可用，请核对后使用其他登录名。"
              : "创建失败，请核对登录名和密码要求。",
          );
        return;
      }
      onSaved();
    } catch {
      form.resetFields(["password"]);
      if (sent) setBlocked(true);
      setError(
        sent
          ? "创建结果尚未确认，请关闭后按登录名检索核对。"
          : "无法获取安全令牌，请重试。",
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
      onBusy(false);
    }
  }
  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={save}
      disabled={busy || blocked}
    >
      <Typography.Title level={5}>创建账号</Typography.Title>
      <p>新账号默认没有业务权限，请另行配置授权。</p>
      {error ? <Alert title={error} type="error" /> : null}
      <Form.Item
        name="login"
        label="登录名"
        rules={[{ required: true, whitespace: true, message: "请输入登录名" }]}
      >
        <Input maxLength={150} autoComplete="off" />
      </Form.Item>
      <Form.Item
        name="password"
        label="初始密码"
        rules={[{ required: true, min: 12, message: "密码至少12字符" }]}
        extra="至少12字符，UTF-8不超过72字节。"
      >
        <Input.Password autoComplete="new-password" />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={busy}>
        创建账号
      </Button>
    </Form>
  );
}
export function AccountEditForm({
  account,
  onBusy,
  onSaved,
}: {
  account: View;
  onBusy: (value: boolean) => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<{ password: string }>();
  const [busy, setBusy] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const inFlight = useRef(false);
  const snapshot = useRef({
    id: account.id!,
    version: account.version!,
    active: account.active!,
  });
  async function perform(
    operation: "status" | "reset-password" | "revoke-sessions",
    password?: string,
  ) {
    if (inFlight.current || blocked) return;
    inFlight.current = true;
    setBusy(true);
    onBusy(true);
    setError("");
    let sent = false;
    try {
      const token = await csrf();
      const headers = { [token.headerName!]: token.token! };
      const params = { path: { id: snapshot.current.id } };
      sent = true;
      const result =
        operation === "status"
          ? await api.POST("/api/authorization/account-lifecycle/{id}/status", {
              params,
              headers,
              body: {
                expectedVersion: snapshot.current.version,
                active: !snapshot.current.active,
              },
            })
          : operation === "reset-password"
            ? await api.POST(
                "/api/authorization/account-lifecycle/{id}/reset-password",
                {
                  params,
                  headers,
                  body: { expectedVersion: snapshot.current.version, password },
                },
              )
            : await api.POST(
                "/api/authorization/account-lifecycle/{id}/revoke-sessions",
                {
                  params,
                  headers,
                  body: { expectedVersion: snapshot.current.version },
                },
              );
      form.resetFields(["password"]);
      if (!result.response.ok) {
        const status = result.response.status,
          code = (result.error as { code?: string } | undefined)?.code;
        if (status === 403 || status === 409 || status === 408 || status >= 500)
          setBlocked(true);
        setError(
          code === "LAST_AUTHORIZATION_ADMINISTRATOR"
            ? "不能停用最后一位有效权限管理员。"
            : status === 409
              ? "账号状态已变化，请关闭后重新读取。"
              : status === 403
                ? "无权操作该账号，请重新读取。"
                : status === 408 || status >= 500
                  ? "操作结果尚未确认，请关闭后重新读取核对。"
                  : "操作失败，请检查密码要求。",
        );
        return;
      }
      onSaved();
    } catch {
      form.resetFields(["password"]);
      if (sent) setBlocked(true);
      setError(
        sent
          ? "操作结果尚未确认，请关闭后重新读取核对。"
          : "无法获取安全令牌，请重试。",
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
      onBusy(false);
    }
  }
  return (
    <div>
      <Typography.Title level={5}>
        {account.login}（{account.active ? "可用" : "已停用"}）
      </Typography.Title>
      {error ? <Alert title={error} type="error" /> : null}
      <Space wrap>
        <Button
          disabled={busy || blocked}
          onClick={() => void perform("status")}
        >
          {account.active ? "停用账号" : "启用账号"}
        </Button>
        <Button
          disabled={busy || blocked}
          onClick={() => void perform("revoke-sessions")}
        >
          撤销所有登录
        </Button>
      </Space>
      <Form
        form={form}
        layout="vertical"
        onFinish={(values) => void perform("reset-password", values.password)}
        disabled={busy || blocked}
      >
        <Form.Item
          name="password"
          label="重置密码"
          rules={[{ required: true, min: 12, message: "密码至少12字符" }]}
          extra="至少12字符，UTF-8不超过72字节。"
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button htmlType="submit" loading={busy}>
          重置密码并撤销登录
        </Button>
      </Form>
    </div>
  );
}
