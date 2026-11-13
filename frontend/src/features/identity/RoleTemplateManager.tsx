import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Radio,
  Segmented,
  Select,
  Space,
  Switch,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import {
  actionIndex,
  actionOptionGroups,
  groupActions,
  permissionCatalogQuery,
  type PermissionAction,
} from "../../shared/permissions";
import type { components } from "../../shared/generated/api";
import { newRequestId } from "../../shared/ids";
type Template = components["schemas"]["RoleTemplate"];
type StartingTemplate = components["schemas"]["StartingTemplate"];
type Create = components["schemas"]["CreateRole"];
type Change = components["schemas"]["RoleChange"];
type Draft = {
  code: string;
  name: string;
  active: boolean;
  actions: NonNullable<Change["actions"]>;
};
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
  const [creatingMode, setCreatingMode] = useState<"template" | "blank">(
    "template",
  );
  const [generated, setGenerated] = useState<Template | null>(null);
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
  // 角色可选动作与账号授权共用同一份服务端目录。
  const catalog = useQuery({ ...permissionCatalogQuery(), enabled: open });
  function close() {
    if (!busy) {
      setOpen(false);
      setCreating(false);
      resetCreateFlow();
      setSelectedId(undefined);
      onClosed();
    }
  }
  function resetCreateFlow() {
    setCreatingMode("template");
    setGenerated(null);
  }
  const error = list.error || selected.error || catalog.error;
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
              resetCreateFlow();
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
        {generated ? (
          <>
            <Alert
              type="success"
              showIcon
              title={`已生成「${generated.name}」（${generated.code}），${generated.actions?.length ?? 0} 个动作，当前版本 ${generated.version}，尚未分配给任何账号。`}
            />
            <Space wrap style={{ marginTop: 16 }}>
              <Button
                type="primary"
                onClick={() => {
                  setGenerated(null);
                  setCreating(false);
                  setSelectedId(generated.id);
                  void list.refetch();
                }}
              >
                继续编辑这个角色
              </Button>
              <Button
                onClick={() => {
                  setNotice(
                    `已生成「${generated.name}」（${generated.code}），可在角色列表里继续编辑或分配给账号。`,
                  );
                  setGenerated(null);
                  setCreating(false);
                  void list.refetch();
                }}
              >
                稍后处理
              </Button>
            </Space>
          </>
        ) : creating ? (
          <>
            <Segmented
              style={{ marginBottom: 16 }}
              value={creatingMode}
              onChange={(value) =>
                setCreatingMode(value as "template" | "blank")
              }
              options={[
                { label: "从模板生成", value: "template" },
                { label: "空白创建", value: "blank" },
              ]}
              disabled={busy}
            />
            {creatingMode === "template" ? (
              <StartingRoleCreator
                catalog={catalog.data ?? []}
                onBusy={setBusy}
                onGenerated={(role) => {
                  setGenerated(role);
                  void list.refetch();
                }}
                onCancel={() => {
                  setCreating(false);
                  resetCreateFlow();
                }}
              />
            ) : (
              <RoleTemplateForm
                key="create"
                actions={actionOptionGroups(catalog.data ?? [])}
                onBusy={setBusy}
                onSaved={saved}
              />
            )}
          </>
        ) : selectedId &&
          selected.data &&
          !selected.isFetching &&
          !selected.error ? (
          <RoleTemplateForm
            key={`${selectedId}:${selected.data.version}`}
            selected={selected.data}
            actions={actionOptionGroups(catalog.data ?? [])}
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
  actions,
  onBusy,
  onSaved,
}: {
  selected?: Template;
  /** 由服务端动作目录生成的分组选项。 */
  actions: ReturnType<typeof actionOptionGroups>;
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
  // 请求身份只在首次渲染生成一次；useRef 的参数每次渲染都会求值，不能直接写 newRequestId()。
  const [requestId] = useState(() => newRequestId());
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
        requestId,
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

/**
 * 从预设模板生成角色。
 *
 * <p>模板只提供初始动作：选模板 → 生成 → 再编辑。生成成功后由父组件切换到普通编辑表单；
 * 不在这里维护动作集合，避免前端出现第二份岗位清单。
 */
export function StartingRoleCreator({
  catalog,
  onBusy,
  onGenerated,
  onCancel,
}: {
  catalog: PermissionAction[];
  onBusy: (busy: boolean) => void;
  onGenerated: (role: Template) => void;
  onCancel: () => void;
}) {
  const [templateCode, setTemplateCode] = useState<string>();
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [requestId, setRequestId] = useState(() => newRequestId());
  const inFlight = useRef(false);
  const templates = useQuery({
    queryKey: ["role-starting-templates"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/authorization/role-templates/starting",
        { signal },
      );
      if (!response.ok || !data) throw new Error("无法读取预设角色模板");
      return data;
    },
  });
  const selected = templates.data?.find((item) => item.code === templateCode);
  const index = actionIndex(catalog);
  const groups = selected
    ? groupActions(
        (selected.actions ?? [])
          .map((action) => index.get(action))
          .filter((action): action is PermissionAction => !!action),
      )
    : [];
  const unknown = (selected?.actions ?? []).filter(
    (action) => !index.has(action),
  );

  function choose(item: StartingTemplate) {
    setTemplateCode(item.code);
    setName(item.name ?? "");
    setCode((item.code ?? "").toUpperCase());
    setError("");
    setUncertain(false);
    setRequestId(newRequestId());
  }

  /** 参数变化后换请求身份；结果未确认时保持不变，便于原参数重试。 */
  function edited() {
    if (!uncertain) setRequestId(newRequestId());
    setError("");
  }

  async function generate() {
    if (inFlight.current || busy) return;
    if (!selected) {
      setError("请先选择一个模板");
      return;
    }
    const cleanName = name.trim();
    const cleanCode = code.trim().toUpperCase();
    if (!cleanName) {
      setError("请填写角色名称");
      return;
    }
    if (!/^[A-Z][A-Z0-9_]{2,79}$/.test(cleanCode)) {
      setError("角色代码需为 3 到 80 位大写字母、数字或下划线，字母开头");
      return;
    }
    inFlight.current = true;
    setBusy(true);
    onBusy(true);
    setError("");
    try {
      const token = await csrf();
      const result = await api.POST(
        "/api/authorization/role-templates/from-template",
        {
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId,
            templateCode: selected.code,
            name: cleanName,
            code: cleanCode,
          },
        },
      );
      if (!result.response.ok) {
        const status = result.response.status;
        const problem = result.error as { code?: string } | undefined;
        if (status >= 500) {
          setUncertain(true);
          setError("生成结果尚未确认；请用相同参数重试。");
          return;
        }
        setError(
          problem?.code === "ROLE_CODE_EXISTS"
            ? "角色代码已存在，请修改代码，或检索并打开已有角色；系统不会覆盖。"
            : problem?.code === "ROLE_REQUEST_MISMATCH"
              ? "创建请求已用于其他参数，请重新选择模板后再试。"
              : status === 403
                ? "没有维护权限或影响账号已超出管理范围。"
                : status === 400
                  ? "模板或输入无效，请核对后重试。"
                  : "生成失败，请重试。",
        );
        return;
      }
      if (!result.data) {
        setUncertain(true);
        setError("生成结果尚未确认；请用相同参数重试。");
        return;
      }
      setUncertain(false);
      onGenerated(result.data);
    } catch {
      setUncertain(true);
      setError("生成结果尚未确认；请用相同参数重试。");
    } finally {
      inFlight.current = false;
      setBusy(false);
      onBusy(false);
    }
  }

  return (
    <div>
      <Alert
        type="info"
        showIcon
        title="使用方式：① 选一个预设模板 → ② 生成角色 → ③ 再按需要增删动作。"
        description="模板只决定初始动作，生成后就是普通角色；不会自动分配给账号，也不会随后续模板调整自动变化。机构负责人不需要选模板，直接使用平台开通的租户管理员。"
      />
      {templates.error ? (
        <Alert type="error" showIcon title={templates.error.message} />
      ) : null}
      <Typography.Title level={5} style={{ marginTop: 16 }}>
        选择预设模板
      </Typography.Title>
      {templates.isPending ? (
        <p role="status">正在读取预设模板…</p>
      ) : (
        <Radio.Group
          value={templateCode}
          disabled={busy || uncertain}
          onChange={(event) => {
            const item = templates.data?.find(
              (entry) => entry.code === event.target.value,
            );
            if (item) choose(item);
          }}
        >
          <Space orientation="vertical">
            {(templates.data ?? []).map((item) => (
              <Radio key={item.code} value={item.code}>
                {item.name}（{item.code}）· {item.actionCount} 个动作
              </Radio>
            ))}
          </Space>
        </Radio.Group>
      )}
      {selected ? (
        <div
          style={{
            border: "1px solid #e8e8e8",
            borderRadius: 8,
            padding: 12,
            marginTop: 12,
          }}
        >
          <Typography.Text strong>{selected.name}</Typography.Text>{" "}
          <Typography.Text type="secondary">{selected.purpose}</Typography.Text>
          {groups.map((group) => (
            <p key={group.group} style={{ margin: "6px 0" }}>
              <Typography.Text strong>{group.group}</Typography.Text>：
              {group.actions
                .map((action) => action.label ?? action.code)
                .join("、")}
            </p>
          ))}
          {unknown.length > 0 ? <p>未登记动作：{unknown.join("、")}</p> : null}
          <Typography.Text type="secondary">
            数据范围仍需生成后按学校分配。
          </Typography.Text>
        </div>
      ) : (
        <p style={{ marginTop: 12 }}>先选择一个模板。</p>
      )}
      <div style={{ marginTop: 16 }}>
        <label htmlFor="starting-role-name">角色名称</label>
        <Input
          id="starting-role-name"
          value={name}
          maxLength={100}
          disabled={busy || uncertain}
          onChange={(event) => {
            edited();
            setName(event.target.value);
          }}
        />
        <label
          htmlFor="starting-role-code"
          style={{ display: "block", marginTop: 8 }}
        >
          角色代码
        </label>
        <Input
          id="starting-role-code"
          value={code}
          maxLength={80}
          disabled={busy || uncertain}
          onChange={(event) => {
            edited();
            setCode(event.target.value.toUpperCase());
          }}
        />
      </div>
      {error ? (
        <Alert type="error" showIcon title={error} style={{ marginTop: 12 }} />
      ) : null}
      <Space wrap style={{ marginTop: 16 }}>
        <Button
          type="primary"
          loading={busy}
          disabled={!selected || uncertain}
          onClick={() => void generate()}
        >
          按模板生成角色
        </Button>
        {uncertain ? (
          <Button disabled={busy} onClick={() => void generate()}>
            重试相同生成
          </Button>
        ) : null}
        <Button disabled={busy} onClick={onCancel}>
          取消
        </Button>
      </Space>
    </div>
  );
}
