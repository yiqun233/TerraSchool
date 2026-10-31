import { useRef, useState } from "react";
import { Alert, Button, Form, Input, Modal, Select, Space } from "antd";
import { useQuery } from "@tanstack/react-query";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type Unit = components["schemas"]["OrganizationUnit"];
type Create = components["schemas"]["CreateOrganizationUnit"];
type Draft = { schoolId: string; kind: string; name: string };

/** 修改通过完整业务动作提交，不使用查询重试或乐观名称覆盖。 */
export function OrganizationEditor({
  selected,
  onSaved,
  identityKey,
}: {
  selected?: Unit;
  onSaved: () => void;
  identityKey: string;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [schoolSearch, setSchoolSearch] = useState("");
  const [locked, setLocked] = useState(false);
  const [form] = Form.useForm<Draft>();
  const pending = useRef<Create | null>(null);
  const inFlight = useRef(false);
  const renameSnapshot = useRef<{
    id: string;
    version: number | undefined;
  } | null>(null);
  const grants = useQuery({
    queryKey: ["organization-capabilities", identityKey],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/capabilities",
        { signal },
      );
      if (!response.ok) throw new Error("无法读取维护权限");
      return data;
    },
  });
  const schools = grants.data?.editableSchoolIds ?? [];
  const schoolOptions = useQuery({
    queryKey: ["organization-school-options", identityKey, schoolSearch],
    enabled: open && !selected,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/school-options",
        { signal, params: { query: { search: schoolSearch } } },
      );
      if (!response.ok) throw new Error("无法读取学校选项");
      return data;
    },
  });
  const allowed = selected
    ? selected.kind !== "SCHOOL" && schools.includes(selected.schoolId ?? "")
    : schools.length > 0;
  if (!allowed || grants.error) return null;
  function begin() {
    setError("");
    setLocked(false);
    pending.current = null;
    renameSnapshot.current = selected
      ? { id: selected.id!, version: selected.version }
      : null;
    form.setFieldsValue({
      schoolId: selected?.schoolId,
      kind: selected?.kind ?? "CAMPUS",
      name: selected?.name ?? "",
    });
    setOpen(true);
  }
  async function save(values: Draft) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const headers = { [token.headerName!]: token.token! };
      if (selected) {
        const snapshot = renameSnapshot.current!;
        const result = await api.POST("/api/organization/units/{id}/rename", {
          params: { path: { id: snapshot.id } },
          headers,
          body: { expectedVersion: snapshot.version, name: values.name },
        });
        if (!result.response.ok)
          throw new Error(
            result.response.status === 409
              ? "版本已变更，请关闭窗口并刷新后核对名称"
              : "改名被拒绝，请核对权限和名称",
          );
      } else {
        pending.current ??= {
          requestId: crypto.randomUUID(),
          schoolId: values.schoolId,
          kind: values.kind,
          name: values.name,
        };
        setLocked(true);
        const result = await api.POST("/api/organization/units", {
          headers,
          body: pending.current,
        });
        if (!result.response.ok) {
          // 网关错误或服务器响应失败不能证明事务未提交，必须保留同一请求身份。
          if (result.response.status >= 500 || result.response.status === 408)
            throw new Error("提交结果尚未确认");
          pending.current = null;
          setLocked(false);
          throw new Error(
            result.response.status === 409
              ? "请求身份与原提交不一致"
              : "新增被拒绝，请核对权限和输入",
          );
        }
      }
      setOpen(false);
      pending.current = null;
      setLocked(false);
      onSaved();
    } catch (e) {
      setError(
        pending.current
          ? "提交结果尚未确认，输入已保留，请点击保存重试同一请求。"
          : (e as Error).message,
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <Button onClick={begin} disabled={open || busy}>
        {selected ? "改名" : "新增校区 / 部门"}
      </Button>
      <Modal
        title={selected ? "组织改名" : "新增校区 / 部门"}
        open={open}
        onCancel={() => {
          if (!locked && !busy) setOpen(false);
        }}
        closable={!locked && !busy}
        keyboard={!locked && !busy}
        mask={{ closable: false }}
        footer={
          <Space>
            <Button disabled={locked || busy} onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button type="primary" loading={busy} onClick={() => form.submit()}>
              保存
            </Button>
          </Space>
        }
      >
        {error ? <Alert title={error} type="error" showIcon /> : null}
        <Form
          form={form}
          layout="vertical"
          onFinish={save}
          disabled={busy || locked}
        >
          {!selected ? (
            <>
              <Form.Item
                name="schoolId"
                label="所属学校"
                rules={[{ required: true, message: "请选择学校" }]}
              >
                <Select
                  disabled={locked || busy}
                  showSearch={{
                    filterOption: false,
                    onSearch: setSchoolSearch,
                  }}
                  placeholder="输入学校名称查找"
                  loading={schoolOptions.isFetching}
                  options={(schoolOptions.data ?? []).map((unit) => ({
                    value: unit.id,
                    label: unit.name,
                  }))}
                />
              </Form.Item>
              <Form.Item name="kind" label="类型" rules={[{ required: true }]}>
                <Select
                  disabled={locked || busy}
                  options={[
                    { value: "CAMPUS", label: "校区" },
                    { value: "DEPARTMENT", label: "部门" },
                  ]}
                />
              </Form.Item>
            </>
          ) : null}
          <Form.Item
            name="name"
            label="名称"
            rules={[
              { required: true, whitespace: true, message: "请输入名称" },
              { max: 100, message: "名称最多100字符" },
            ]}
          >
            <Input disabled={locked || busy} maxLength={100} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
