import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import {
  Page,
  PageHeader,
  PageState,
  Panel,
  StatusTag,
  TableFooter,
} from "../../shared/ui/Page";
import { ResourceDetails } from "./ResourceDetails";
import { PersonnelAttachments } from "./PersonnelAttachments";
type Kind = "EMPLOYEE" | "TEACHER" | "ROOM";
type Resource = components["schemas"]["ResourceView"];
type Create = components["schemas"]["CreateResource"];
type Draft = {
  schoolId: string;
  name: string;
  campusId?: string;
  departmentId?: string;
  employeeId?: string;
  capacity?: number;
  active: boolean;
};
const labels: Record<Kind, string> = {
  EMPLOYEE: "员工",
  TEACHER: "教师",
  ROOM: "教室",
};

export function ResourceWorkspace({ kind }: { kind: Kind }) {
  const [page, setPage] = useState(0),
    [selected, setSelected] = useState<Resource>();
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [locked, setLocked] = useState(false);
  const capability = useQuery({
    queryKey: ["resource-capabilities", kind],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/resources/{kind}/capabilities",
        { signal, params: { path: { kind } } },
      );
      if (!response.ok || !data) throw new Error("当前账号无此资源查看权限");
      return data;
    },
  });
  const list = useQuery({
    queryKey: ["resources", kind, page],
    enabled: !!capability.data && !capability.error,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/resources/{kind}",
        { signal, params: { path: { kind }, query: { page, size: 20 } } },
      );
      if (!response.ok || !data)
        throw new Error("无法读取资源，请核对当前权限");
      return data;
    },
  });
  const writable = capability.data?.editableSchoolIds ?? [];
  function close() {
    if (!busy && !locked) {
      setOpen(false);
      setSelected(undefined);
    }
  }

  if (capability.isPending)
    return (
      <PageState
        title={labels[kind]}
        description={`正在确认当前账号的${labels[kind]}权限…`}
        loading
      />
    );
  if (capability.error)
    return (
      <PageState
        title={labels[kind]}
        stateTitle={`无法读取${labels[kind]}权限`}
        stateHint={capability.error.message}
        action={<Button onClick={() => void capability.refetch()}>重试</Button>}
      />
    );
  if (list.error)
    return (
      <PageState
        title={labels[kind]}
        stateTitle={`${labels[kind]}列表加载失败`}
        stateHint={list.error.message}
        action={<Button onClick={() => void list.refetch()}>重试</Button>}
      />
    );

  return (
    <Page>
      <PageHeader
        title={labels[kind]}
        description={
          kind === "EMPLOYEE"
            ? "员工按学校维护；可绑定登录账号、维护资质附件与外部资料。停用只影响后续选择，历史业务保留。"
            : kind === "TEACHER"
              ? "教师按学校维护；可关联员工并维护授课资质。停用后不再参与新排课。"
              : "教室按学校维护；容量参与排课校验，未设置容量视为不限。"
        }
        actions={
          <>
            <Button
              type="primary"
              disabled={!writable.length || !!capability.error}
              onClick={() => {
                setSelected(undefined);
                setOpen(true);
              }}
            >
              新增{labels[kind]}
            </Button>
            <Button
              onClick={() => void list.refetch()}
              disabled={!capability.data}
            >
              刷新
            </Button>
          </>
        }
      />
      <Panel
        ariaLabel={`${labels[kind]}列表`}
        footer={
          <TableFooter
            total={list.data?.total ?? 0}
            page={page}
            pageSize={20}
            onChange={setPage}
            unit={`名${labels[kind]}`}
            noun={labels[kind]}
          />
        }
      >
        <Table<Resource>
          rowKey="id"
          loading={capability.isPending || list.isFetching}
          dataSource={
            capability.error || list.error ? [] : (list.data?.items ?? [])
          }
          columns={[
            { title: "名称", dataIndex: "name" },
            { title: "学校", dataIndex: "schoolName" },
            ...(kind === "ROOM"
              ? [
                  {
                    title: "容量",
                    dataIndex: "capacity",
                    render: (value: number | null) => value ?? "未设置",
                  },
                ]
              : []),
            {
              title: "状态",
              dataIndex: "active",
              render: (active: boolean) =>
                active ? (
                  <StatusTag tone="ok">可用</StatusTag>
                ) : (
                  <StatusTag tone="neutral">停用</StatusTag>
                ),
            },
            {
              title: "操作",
              render: (_: unknown, row: Resource) => (
                <Space wrap>
                  <Button
                    disabled={!row.schoolId || !writable.includes(row.schoolId)}
                    onClick={() => {
                      setSelected(row);
                      setOpen(true);
                    }}
                  >
                    编辑
                  </Button>
                  {kind === "TEACHER" ? (
                    <ResourceDetails
                      row={row}
                      mode="qualification"
                      onSaved={() => void list.refetch()}
                    />
                  ) : null}
                  {kind === "EMPLOYEE" ? (
                    <>
                      <ResourceDetails
                        row={row}
                        mode="personnel"
                        onSaved={() => void list.refetch()}
                      />
                      <ResourceDetails
                        row={row}
                        mode="account-link"
                        onSaved={() => void list.refetch()}
                      />
                      <ResourceDetails
                        row={row}
                        mode="external"
                        onSaved={() => void list.refetch()}
                      />
                      <PersonnelAttachments row={row} />
                    </>
                  ) : null}
                </Space>
              ),
            },
          ]}
          pagination={false}
          locale={{ emptyText: "当前范围内暂无资料" }}
        />
      </Panel>
      <Modal
        title={`${selected ? "编辑" : "新增"}${labels[kind]}`}
        open={open}
        onCancel={close}
        closable={!busy && !locked}
        mask={{ closable: !busy && !locked }}
        keyboard={!busy && !locked}
        footer={null}
        destroyOnHidden
      >
        {open ? (
          <ResourceForm
            kind={kind}
            selected={selected}
            onBusy={setBusy}
            onLocked={setLocked}
            onSaved={() => {
              setOpen(false);
              setSelected(undefined);
              setLocked(false);
              void list.refetch();
            }}
            onCancel={close}
          />
        ) : null}
      </Modal>
    </Page>
  );
}

export function ResourceForm({
  kind,
  selected,
  onBusy,
  onLocked,
  onSaved,
  onCancel,
}: {
  kind: Kind;
  selected?: Resource;
  onBusy: (value: boolean) => void;
  onLocked: (value: boolean) => void;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [form] = Form.useForm<Draft>();
  const [searches, setSearches] = useState({
    SCHOOL: "",
    CAMPUS: "",
    DEPARTMENT: "",
    EMPLOYEE: "",
  });
  const searchProps = (type: keyof typeof searches) => ({
    filterOption: false,
    onSearch: (value: string) =>
      setSearches((current) => ({ ...current, [type]: value })),
  });
  const school = Form.useWatch("schoolId", form);
  const [busy, setBusy] = useState(false),
    [locked, setLocked] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const inFlight = useRef(false),
    pending = useRef<Create | null>(null),
    snapshot = useRef(
      selected
        ? {
            id: selected.id!,
            version: selected.version!,
            schoolId: selected.schoolId!,
          }
        : null,
    );
  function useOptions(
    type: "SCHOOL" | "CAMPUS" | "DEPARTMENT" | "EMPLOYEE",
    enabled: boolean,
  ) {
    return useQuery({
      queryKey: ["resource-options", kind, type, school, searches[type]],
      enabled,
      queryFn: async ({ signal }) => {
        const { data, response } = await api.GET(
          "/api/organization/resources/{kind}/options",
          {
            signal,
            params: {
              path: { kind },
              query: {
                type,
                schoolId: type === "SCHOOL" ? undefined : school,
                search: searches[type],
              },
            },
          },
        );
        if (!response.ok || !data)
          throw new Error(
            type === "EMPLOYEE"
              ? "无法读取可关联员工，请核对员工查看权限"
              : "无法读取学校或组织选项",
          );
        return data;
      },
    });
  }
  const schools = useOptions("SCHOOL", !selected),
    campuses = useOptions(
      "CAMPUS",
      !!school && (kind === "EMPLOYEE" || kind === "ROOM"),
    ),
    departments = useOptions("DEPARTMENT", !!school && kind === "EMPLOYEE"),
    employees = useOptions("EMPLOYEE", !!school && kind === "TEACHER");
  const opts = (items: components["schemas"]["ResourceOption"][] | undefined) =>
    (items ?? []).map((item) => ({ value: item.id!, label: item.name! }));
  function fields(values: Draft) {
    return {
      name: values.name,
      ...(kind === "EMPLOYEE"
        ? { campusId: values.campusId, departmentId: values.departmentId }
        : kind === "TEACHER"
          ? { employeeId: values.employeeId }
          : { campusId: values.campusId, capacity: values.capacity }),
    };
  }
  async function save(values: Draft) {
    if (inFlight.current || blocked) return;
    inFlight.current = true;
    setBusy(true);
    onBusy(true);
    setError("");
    let sent = false;
    try {
      if (!snapshot.current && !pending.current)
        pending.current = {
          requestId: crypto.randomUUID(),
          schoolId: values.schoolId,
          ...fields(values),
        };
      const token = await csrf();
      const headers = { [token.headerName!]: token.token! };
      sent = true;
      const result = snapshot.current
        ? await api.POST("/api/organization/resources/{kind}/{id}/update", {
            params: { path: { kind, id: snapshot.current.id } },
            headers,
            body: {
              expectedVersion: snapshot.current.version,
              active: values.active,
              ...fields(values),
            },
          })
        : await api.POST("/api/organization/resources/{kind}", {
            params: { path: { kind } },
            headers,
            body: pending.current!,
          });
      if (!result.response.ok) {
        const status = result.response.status,
          code = (result.error as { code?: string } | undefined)?.code;
        if (status >= 500 || status === 408) {
          if (snapshot.current) setBlocked(true);
          else {
            setLocked(true);
            onLocked(true);
          }
          setError(
            snapshot.current
              ? "保存结果尚未确认，请关闭后刷新核对。"
              : "创建结果尚未确认，请重试相同请求。",
          );
        } else if (locked) {
          setLocked(false);
          onLocked(false);
          setBlocked(true);
          setError(
            "之前的创建结果仍未确认，请关闭后先核对资源目录，勿直接重复创建。",
          );
        } else if (
          status === 409 &&
          (code === "VERSION_CONFLICT" || code === "IDEMPOTENCY_MISMATCH")
        ) {
          setBlocked(true);
          setError("资料或请求身份已变化，请关闭后刷新核对。");
        } else if (status === 403) {
          setBlocked(true);
          setError("无权操作或关联资源不可使用，请关闭后核对。");
        } else {
          if (!snapshot.current) pending.current = null;
          setError(
            code === "EMPLOYEE_ALREADY_TEACHER"
              ? "该员工已关联教师，请核对。"
              : "保存失败，请核对字段及关联。",
          );
        }
        return;
      }
      pending.current = null;
      setLocked(false);
      onLocked(false);
      onSaved();
    } catch {
      if (sent) {
        if (snapshot.current) setBlocked(true);
        else {
          setLocked(true);
          onLocked(true);
        }
      } else if (!locked) pending.current = null;
      setError(
        sent
          ? snapshot.current
            ? "保存结果尚未确认，请关闭后刷新核对。"
            : "创建结果尚未确认，请重试相同请求。"
          : "无法获取安全令牌，请重试。",
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
      onBusy(false);
    }
  }
  const disabled = busy || locked || blocked;
  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{
        schoolId: selected?.schoolId,
        name: selected?.name ?? "",
        campusId: selected?.campusId,
        departmentId: selected?.departmentId,
        employeeId: selected?.employeeId,
        capacity: selected?.capacity,
        active: selected?.active ?? true,
      }}
      onFinish={save}
    >
      {error ? <Alert title={error} type="error" /> : null}
      {schools.error ||
      campuses.error ||
      departments.error ||
      employees.error ? (
        <Alert
          type="warning"
          title={
            (
              schools.error ||
              campuses.error ||
              departments.error ||
              employees.error
            )?.message
          }
        />
      ) : null}
      {selected ? (
        <>
          <p>所属学校：{selected.schoolName}</p>
          <Form.Item name="schoolId" hidden>
            <Input type="hidden" />
          </Form.Item>
        </>
      ) : (
        <Form.Item
          name="schoolId"
          label="学校"
          rules={[{ required: true, message: "请选择学校" }]}
        >
          <Select
            disabled={disabled}
            options={opts(schools.data)}
            showSearch={searchProps("SCHOOL")}
            onChange={() =>
              form.setFieldsValue({
                campusId: undefined,
                departmentId: undefined,
                employeeId: undefined,
              })
            }
          />
        </Form.Item>
      )}
      <Form.Item
        name="name"
        label={`${labels[kind]}名称`}
        rules={[{ required: true, whitespace: true, message: "请输入名称" }]}
      >
        <Input maxLength={100} disabled={disabled} />
      </Form.Item>
      {kind !== "TEACHER" ? (
        <Form.Item name="campusId" label="所属校区（可选）">
          <Select
            allowClear
            disabled={disabled || !school}
            options={opts(campuses.data)}
            showSearch={searchProps("CAMPUS")}
          />
        </Form.Item>
      ) : null}
      {kind === "EMPLOYEE" ? (
        <Form.Item name="departmentId" label="所属部门（可选）">
          <Select
            allowClear
            disabled={disabled || !school}
            options={opts(departments.data)}
            showSearch={searchProps("DEPARTMENT")}
          />
        </Form.Item>
      ) : null}
      {kind === "TEACHER" ? (
        <Form.Item name="employeeId" label="关联员工（可选）">
          <Select
            allowClear
            disabled={disabled || !school}
            options={opts(employees.data)}
            showSearch={searchProps("EMPLOYEE")}
          />
        </Form.Item>
      ) : null}
      {kind === "ROOM" ? (
        <Form.Item name="capacity" label="容量（可选）">
          <InputNumber min={0} max={100000} precision={0} disabled={disabled} />
        </Form.Item>
      ) : null}
      {selected ? (
        <Form.Item name="active" label="可用" valuePropName="checked">
          <Switch disabled={disabled} />
        </Form.Item>
      ) : null}
      <Space>
        <Button
          type="primary"
          htmlType="submit"
          loading={busy}
          disabled={blocked}
        >
          {locked ? "重试相同创建" : "保存"}
        </Button>
        <Button onClick={onCancel} disabled={busy || locked}>
          取消
        </Button>
      </Space>
    </Form>
  );
}
