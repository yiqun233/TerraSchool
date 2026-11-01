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

type Account = components["schemas"]["AccountAuthorization"];
type Replacement = components["schemas"]["Replacement"];
type Option = components["schemas"]["Option"];
type RoleOption = components["schemas"]["RoleOption"];
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
const functional = "FUNCTION";
function options(...lists: (Option[] | undefined)[]) {
  const merged = new Map<string, string>();
  for (const list of lists)
    for (const item of list ?? [])
      if (item.id && item.name) merged.set(item.id, item.name);
  return [...merged].map(([value, label]) => ({ value, label }));
}
function roleOptions(...lists: (RoleOption[] | undefined)[]) {
  const merged = new Map<
    string,
    { value: string; label: string; version: number }
  >();
  for (const list of lists)
    for (const item of list ?? [])
      if (item.id && item.name && item.version != null)
        merged.set(item.id, {
          value: item.id,
          label: item.name,
          version: item.version,
        });
  return [...merged.values()];
}

/** 独立管理窗口；选择账号后才加载规则，服务端负责真正的委派和最后管理员判断。 */
export function AuthorizationManager({ onChanged }: { onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const [accountId, setAccountId] = useState<string>();
  const [accountSearch, setAccountSearch] = useState("");
  const [schoolSearch, setSchoolSearch] = useState("");
  const [roleSearch, setRoleSearch] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const accounts = useQuery({
    queryKey: ["authorization-accounts", accountSearch],
    enabled: open,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/authorization/accounts", {
        signal,
        params: { query: { search: accountSearch } },
      });
      if (!response.ok || !data)
        throw new Error("无法读取可管理账号，请检查当前权限");
      return data;
    },
  });
  const schools = useQuery({
    queryKey: ["authorization-schools", schoolSearch],
    enabled: open,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/authorization/schools", {
        signal,
        params: { query: { search: schoolSearch } },
      });
      if (!response.ok || !data) throw new Error("无法读取可管理学校");
      return data;
    },
  });
  const roles = useQuery({
    queryKey: ["authorization-roles", roleSearch],
    enabled: open,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/authorization/roles", {
        signal,
        params: { query: { search: roleSearch } },
      });
      if (!response.ok || !data) throw new Error("无法读取可分配角色");
      return data;
    },
  });
  const account = useQuery({
    queryKey: ["account-authorization", accountId],
    enabled: open && !!accountId,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/authorization/accounts/{id}",
        { signal, params: { path: { id: accountId! } } },
      );
      if (!response.ok || !data || data.version == null)
        throw new Error("无法读取该账号授权，请检查管理范围");
      return data;
    },
  });
  const queryError =
    accounts.error || schools.error || roles.error || account.error;
  const close = () => {
    if (!busy) {
      setOpen(false);
      setAccountId(undefined);
      // 每次关闭都重读本人权限及业务缓存，覆盖已提交但响应丢失的情形。
      onChanged();
    }
  };
  return (
    <>
      <Button
        onClick={() => {
          setNotice("");
          setOpen(true);
        }}
      >
        管理账号授权
      </Button>
      <Modal
        title="账号授权管理"
        width={980}
        open={open}
        onCancel={close}
        mask={{ closable: !busy }}
        closable={!busy}
        keyboard={!busy}
        footer={
          <Button disabled={busy} onClick={close}>
            关闭管理
          </Button>
        }
      >
        <p>
          修改整套直接权限和学校角色分配。拒绝优先；功能级允许不授予任何学校范围。至少保留一位覆盖全部学校的有效权限管理员。
        </p>
        {notice ? <Alert title={notice} type="success" /> : null}
        <Select
          aria-label="选择账号"
          placeholder="检索并选择可管理账号"
          style={{ width: "100%" }}
          showSearch={{ filterOption: false, onSearch: setAccountSearch }}
          options={
            accounts.error || accounts.isFetching ? [] : options(accounts.data)
          }
          value={accountId}
          onChange={(value) => {
            setNotice("");
            setAccountId(value);
          }}
          disabled={busy}
        />
        {queryError ? <Alert title={queryError.message} type="error" /> : null}
        {account.isFetching && accountId ? (
          <p role="status">正在读取账号授权…</p>
        ) : null}
        {accountId && account.data && !account.isFetching && !account.error ? (
          <AuthorizationForm
            key={`${accountId}:${account.data.version}`}
            account={account.data}
            schools={options(account.data.schoolLabels, schools.data)}
            roles={roleOptions(account.data.roleLabels, roles.data)}
            onSchoolSearch={setSchoolSearch}
            onRoleSearch={setRoleSearch}
            onBusy={setBusy}
            onSaved={() => {
              setNotice("授权已保存。请重新选择账号核对最新结果。");
              setAccountId(undefined);
            }}
          />
        ) : null}
      </Modal>
    </>
  );
}

export function AuthorizationForm({
  account,
  schools,
  roles,
  onSchoolSearch,
  onRoleSearch,
  onBusy,
  onSaved,
}: {
  account: Account;
  schools: { value: string; label: string }[];
  roles: { value: string; label: string; version: number }[];
  onSchoolSearch: (value: string) => void;
  onRoleSearch: (value: string) => void;
  onBusy: (value: boolean) => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<Replacement>();
  const [busy, setBusy] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  // 版本固定为打开编辑时读取的值，查询刷新不能暗中替换expectedVersion。
  const snapshot = useRef({
    id: account.accountId!,
    version: account.version!,
  });
  async function save(values: Replacement) {
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
        "/api/authorization/accounts/{id}/replace",
        {
          params: { path: { id: snapshot.current.id } },
          headers: { [token.headerName!]: token.token! },
          body: {
            expectedVersion: snapshot.current.version,
            rules: (values.rules ?? []).map((rule) => ({
              ...rule,
              schoolId:
                rule.schoolId === functional ? undefined : rule.schoolId,
            })),
            assignments: values.assignments ?? [],
          },
        },
      );
      if (!response.ok) {
        const code = (problem as { code?: string } | undefined)?.code;
        const trace = response.headers?.get("X-Trace-Id");
        const reference =
          trace && /^[0-9a-f-]{36}$/i.test(trace)
            ? `（追踪编号：${trace}）`
            : "";
        const message =
          code === "LAST_AUTHORIZATION_ADMINISTRATOR"
            ? "不能移除最后一位有效权限管理员"
            : response.status === 409
              ? "授权已变化，请关闭后重新读取，再决定修改"
              : response.status === 403
                ? "当前没有管理权限或已超出委派范围，请重新读取"
                : response.status >= 500 || response.status === 408
                  ? "保存结果尚未确认，请关闭后重新读取核对"
                  : "保存失败，请核对规则后重试";
        if (
          response.status >= 500 ||
          response.status === 408 ||
          response.status === 409 ||
          response.status === 403
        )
          setBlocked(true);
        setError(message + reference);
        return;
      }
      onSaved();
    } catch {
      if (sent) setBlocked(true);
      setError(
        sent
          ? "保存结果尚未确认，请关闭后重新读取核对，勿直接重复提交"
          : "无法获取安全令牌，请重试",
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
      initialValues={{
        rules: (account.rules ?? []).map((rule) => ({
          ...rule,
          schoolId: rule.schoolId ?? functional,
        })),
        assignments: account.assignments ?? [],
      }}
      onFinish={save}
      disabled={busy || blocked}
    >
      <Typography.Title level={5}>
        {account.login}
        {account.active ? "" : "（停用账号）"}
      </Typography.Title>
      <p>
        当前有效：可查看{account.effective?.readableSchoolIds?.length ?? 0}
        所，可维护{account.effective?.maintainableSchoolIds?.length ?? 0}
        所，可管理授权{account.effective?.manageableSchoolIds?.length ?? 0}
        所。下方为待保存规则。
      </p>
      {error ? <Alert title={error} type="error" /> : null}
      <Typography.Title level={5}>直接权限</Typography.Title>
      <Form.List name="rules">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <Space key={field.key} wrap align="start">
                <Form.Item
                  name={[field.name, "action"]}
                  rules={[{ required: true, message: "请选择操作" }]}
                >
                  <Select
                    aria-label="权限操作"
                    style={{ width: 150 }}
                    options={actions}
                  />
                </Form.Item>
                <Form.Item
                  name={[field.name, "schoolId"]}
                  rules={[{ required: true, message: "请选择范围" }]}
                >
                  <Select
                    aria-label="权限学校"
                    style={{ width: 250 }}
                    showSearch={{
                      filterOption: false,
                      onSearch: onSchoolSearch,
                    }}
                    options={[
                      { value: functional, label: "仅功能级（不含学校范围）" },
                      ...schools,
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name={[field.name, "effect"]}
                  rules={[{ required: true, message: "请选择效果" }]}
                >
                  <Select
                    aria-label="允许或拒绝"
                    style={{ width: 100 }}
                    options={effects}
                  />
                </Form.Item>
                <Button onClick={() => remove(field.name)}>移除规则</Button>
              </Space>
            ))}
            <Button
              onClick={() =>
                add({
                  action: "ORGANIZATION_READ",
                  schoolId: functional,
                  effect: "ALLOW",
                })
              }
            >
              添加直接权限
            </Button>
          </>
        )}
      </Form.List>
      <Typography.Title level={5}>学校角色分配</Typography.Title>
      <Form.List name="assignments">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <Space key={field.key} wrap align="start">
                <Form.Item
                  name={[field.name, "roleId"]}
                  rules={[{ required: true, message: "请选择角色" }]}
                >
                  <Select
                    aria-label="分配角色"
                    onChange={(value) =>
                      form.setFieldValue(
                        ["assignments", field.name, "expectedRoleVersion"],
                        roles.find((role) => role.value === value)?.version,
                      )
                    }
                    style={{ width: 250 }}
                    showSearch={{ filterOption: false, onSearch: onRoleSearch }}
                    options={roles}
                  />
                </Form.Item>
                <Form.Item
                  name={[field.name, "expectedRoleVersion"]}
                  hidden
                  rules={[
                    { required: true, message: "请重新选择当前版本的角色" },
                  ]}
                >
                  <Input type="hidden" />
                </Form.Item>
                <Form.Item
                  name={[field.name, "schoolId"]}
                  rules={[{ required: true, message: "请选择学校" }]}
                >
                  <Select
                    aria-label="角色学校"
                    style={{ width: 250 }}
                    showSearch={{
                      filterOption: false,
                      onSearch: onSchoolSearch,
                    }}
                    options={schools}
                  />
                </Form.Item>
                <Button onClick={() => remove(field.name)}>移除分配</Button>
              </Space>
            ))}
            <Button onClick={() => add({})}>添加学校角色</Button>
          </>
        )}
      </Form.List>
      <Form.Item style={{ marginTop: 24 }}>
        <Button type="primary" htmlType="submit" loading={busy}>
          保存整套授权
        </Button>
      </Form.Item>
    </Form>
  );
}
