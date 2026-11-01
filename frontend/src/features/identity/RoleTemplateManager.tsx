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
  Switch,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
type Template = components["schemas"]["RoleTemplate"];
type Create = components["schemas"]["CreateRole"];
type Change = components["schemas"]["RoleChange"];
type Draft = {
  code: string;
  name: string;
  active: boolean;
  actions: NonNullable<Change["actions"]>;
};
const actions = [
  { value: "ORGANIZATION_READ", label: "组织查看" },
  { value: "ORGANIZATION_MAINTAIN", label: "组织维护" },
  { value: "AUTHORIZATION_MANAGE", label: "授权管理" },
  { value: "EMPLOYEE_READ", label: "员工查看" },
  { value: "EMPLOYEE_MAINTAIN", label: "员工维护" },
  { value: "TEACHER_READ", label: "教师查看" },
  { value: "TEACHER_MAINTAIN", label: "教师维护" },
  { value: "ROOM_READ", label: "教室查看" },
  { value: "ROOM_MAINTAIN", label: "教室维护" },
  { value: "PERSONNEL_READ", label: "人事档案查看" },
  { value: "PERSONNEL_MAINTAIN", label: "人事档案维护" },
];
const effects = [
  { value: "ALLOW", label: "允许" },
  { value: "DENY", label: "拒绝" },
];

export function RoleTemplateManager({ onClosed }: { onClosed: () => void }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [includeInactive, setIncludeInactive] = useState(true);
  const [selectedId, setSelectedId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const list = useQuery({
    queryKey: ["role-templates", search, includeInactive],
    enabled: open,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/authorization/role-templates",
        { signal, params: { query: { search, includeInactive } } },
      );
      if (!response.ok || !data)
        throw new Error("无法读取可维护角色，请检查当前管理范围");
      return data;
    },
  });
  const selected = useQuery({
    queryKey: ["role-template", selectedId],
    enabled: open && !!selectedId,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/authorization/role-templates/{id}",
        { signal, params: { path: { id: selectedId! } } },
      );
      if (!response.ok || !data || data.version == null)
        throw new Error("该角色当前不可维护，请检查影响账号范围");
      return data;
    },
  });
  function close() {
    if (!busy) {
      setOpen(false);
      setCreating(false);
      setSelectedId(undefined);
      onClosed();
    }
  }
  const error = list.error || selected.error;
  function saved() {
    setNotice("角色模板已保存，请重新选择核对当前结果。");
    setCreating(false);
    setSelectedId(undefined);
    void list.refetch();
  }
  return (
    <>
      <Button
        onClick={() => {
          setNotice("");
          setOpen(true);
        }}
      >
        维护角色模板
      </Button>
      <Modal
        title="角色模板维护"
        width={820}
        open={open}
        onCancel={close}
        mask={{ closable: !busy }}
        closable={!busy}
        keyboard={!busy}
        footer={
          <Button disabled={busy} onClick={close}>
            关闭角色维护
          </Button>
        }
      >
        <p>
          角色按学校分配后生效。修改或停用会影响全部使用该模板的账号；服务端会检查委派范围和最后管理员。
        </p>
        <Space wrap>
          <Switch
            aria-label="包含停用角色"
            checked={includeInactive}
            onChange={setIncludeInactive}
            checkedChildren="含停用"
            unCheckedChildren="仅可用"
            disabled={busy}
          />
          <Select
            aria-label="选择角色模板"
            placeholder="按代码或名称检索角色"
            style={{ width: 380 }}
            value={selectedId}
            disabled={busy}
            showSearch={{ filterOption: false, onSearch: setSearch }}
            options={
              list.error || list.isFetching
                ? []
                : (list.data ?? []).map((role) => ({
                    value: role.id!,
                    label: `${role.name}（${role.code}）${role.active ? "" : " · 停用"}`,
                  }))
            }
            onChange={(value) => {
              setCreating(false);
              setNotice("");
              setSelectedId(value);
            }}
          />
          <Button
            disabled={busy}
            onClick={() => {
              setSelectedId(undefined);
              setCreating(true);
              setNotice("");
            }}
          >
            新建角色
          </Button>
        </Space>
        {notice ? <Alert title={notice} type="success" /> : null}
        {error ? <Alert title={error.message} type="error" /> : null}
        {selectedId && selected.isFetching ? (
          <p role="status">正在读取角色模板…</p>
        ) : null}
        {creating ? (
          <RoleTemplateForm key="create" onBusy={setBusy} onSaved={saved} />
        ) : selectedId &&
          selected.data &&
          !selected.isFetching &&
          !selected.error ? (
          <RoleTemplateForm
            key={`${selectedId}:${selected.data.version}`}
            selected={selected.data}
            onBusy={setBusy}
            onSaved={saved}
          />
        ) : null}
      </Modal>
    </>
  );
}

/** 创建保留请求身份和参数；修改固定打开时版本，结果不确定时不自动重试。 */
export function RoleTemplateForm({
  selected,
  onBusy,
  onSaved,
}: {
  selected?: Template;
  onBusy: (busy: boolean) => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<Draft>();
  const [busy, setBusy] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  const snapshot = useRef(
    selected ? { id: selected.id!, version: selected.version! } : null,
  );
  const requestId = useRef(crypto.randomUUID());
  const pending = useRef<Create | null>(null);
  async function save(values: Draft) {
    if (inFlight.current || blocked) return;
    inFlight.current = true;
    setBusy(true);
    onBusy(true);
    setError("");
    let sent = false;
    if (!snapshot.current && !uncertain)
      pending.current = {
        requestId: requestId.current,
        code: values.code,
        name: values.name,
        actions: values.actions ?? [],
      };
    try {
      const token = await csrf();
      const headers = { [token.headerName!]: token.token! };
      sent = true;
      const result = snapshot.current
        ? await api.POST("/api/authorization/role-templates/{id}/replace", {
            params: { path: { id: snapshot.current.id } },
            headers,
            body: {
              expectedVersion: snapshot.current.version,
              name: values.name,
              active: values.active,
              actions: values.actions ?? [],
            },
          })
        : await api.POST("/api/authorization/role-templates", {
            headers,
            body: pending.current!,
          });
      if (!result.response.ok) {
        const status = result.response.status,
          code = (result.error as { code?: string } | undefined)?.code;
        const trace = result.response.headers?.get("X-Trace-Id");
        const reference =
          trace && /^[0-9a-f-]{36}$/i.test(trace)
            ? `（追踪编号：${trace}）`
            : "";
        const unknown = status >= 500 || status === 408;
        if (snapshot.current && (unknown || status === 409 || status === 403))
          setBlocked(true);
        if (!snapshot.current && unknown) setUncertain(true);
        const message =
          code === "LAST_AUTHORIZATION_ADMINISTRATOR"
            ? "不能停用或撤销最后一位有效权限管理员的角色"
            : code === "ROLE_VERSION_CONFLICT"
              ? "角色定义或分配范围已变化，请关闭后重新读取"
              : code === "ROLE_CODE_EXISTS"
                ? "角色代码已存在，请先检索核对"
                : code === "ROLE_REQUEST_MISMATCH"
                  ? "创建请求身份已用于其他参数，请先核对原创建结果"
                  : status === 403
                    ? "没有维护权限或影响账号已超出管理范围，请重新读取"
                    : unknown
                      ? snapshot.current
                        ? "保存结果尚未确认，请关闭后重新读取核对"
                        : "创建结果尚未确认，请重试相同创建；关闭前应记下角色代码以便核对"
                      : "保存失败，请核对输入后重试";
        setError(message + reference);
        return;
      }
      onSaved();
    } catch {
      if (sent) {
        if (snapshot.current) setBlocked(true);
        else setUncertain(true);
      }
      setError(
        !sent
          ? "无法获取安全令牌，请重试"
          : snapshot.current
            ? "保存结果尚未确认，请关闭后重新读取核对"
            : "创建结果尚未确认，请重试相同创建；关闭后先按代码核对",
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
      onBusy(false);
    }
  }
  return (
    <>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          code: selected?.code ?? "",
          name: selected?.name ?? "",
          active: selected?.active ?? true,
          actions: selected?.actions ?? [],
        }}
        disabled={busy || blocked || uncertain}
        onFinish={save}
      >
        <Typography.Title level={5}>
          {selected ? "编辑角色" : "新建角色"}
        </Typography.Title>
        {selected ? (
          <p>
            当前已分配给{selected.assignedAccounts ?? 0}
            个账号，以下修改将影响这些账号。
          </p>
        ) : (
          <p>创建后默认可用，需另行按学校分配给账号。</p>
        )}
        {error ? <Alert title={error} type="error" /> : null}
        <Form.Item
          name="code"
          label="角色代码"
          rules={[
            { required: true, message: "请输入角色代码" },
            ...(!selected
              ? [
                  {
                    pattern: /^[A-Za-z][A-Za-z0-9_]{2,79}$/,
                    message: "3到80位字母、数字或下划线，字母开头",
                  },
                ]
              : []),
          ]}
        >
          <Input disabled={!!selected || busy || uncertain} maxLength={80} />
        </Form.Item>
        <Form.Item
          name="name"
          label="角色名称"
          rules={[
            { required: true, whitespace: true, message: "请输入角色名称" },
          ]}
        >
          <Input maxLength={100} />
        </Form.Item>
        {selected ? (
          <Form.Item name="active" label="角色可用" valuePropName="checked">
            <Switch />
          </Form.Item>
        ) : null}
        <Typography.Title level={5}>功能动作</Typography.Title>
        <Form.List name="actions">
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <Space key={field.key} wrap align="start">
                  <Form.Item
                    name={[field.name, "action"]}
                    rules={[{ required: true, message: "请选择动作" }]}
                  >
                    <Select
                      aria-label="模板动作"
                      style={{ width: 180 }}
                      options={actions}
                    />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, "effect"]}
                    rules={[{ required: true, message: "请选择效果" }]}
                  >
                    <Select
                      aria-label="模板允许或拒绝"
                      style={{ width: 130 }}
                      options={effects}
                    />
                  </Form.Item>
                  <Button onClick={() => remove(field.name)}>移除动作</Button>
                </Space>
              ))}
              <Button
                onClick={() =>
                  add({ action: "ORGANIZATION_READ", effect: "ALLOW" })
                }
              >
                添加模板动作
              </Button>
            </>
          )}
        </Form.List>
        <Form.Item style={{ marginTop: 24 }}>
          <Button type="primary" htmlType="submit" loading={busy}>
            {selected ? "保存角色模板" : "创建角色模板"}
          </Button>
        </Form.Item>
      </Form>
      {uncertain && !selected ? (
        <Button
          disabled={busy}
          onClick={() => void save(form.getFieldsValue())}
        >
          重试相同创建
        </Button>
      ) : null}
    </>
  );
}
