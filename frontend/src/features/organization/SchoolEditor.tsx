import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Form, Input, Modal, Space, Switch } from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

export function SchoolEditor({
  school,
  onSaved,
}: {
  school: components["schemas"]["OrganizationUnit"];
  onSaved: () => void;
}) {
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const sending = useRef(false),
    client = useQueryClient(),
    id = school.id!;
  const info = useQuery({
    queryKey: ["school-details", id],
    enabled: open,
    retry: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const result = await api.GET("/api/organization/schools/{id}", {
        signal,
        params: { path: { id } },
      });
      if (!result.response.ok || !result.data)
        throw new Error("需要覆盖所有学校的权限管理员。");
      return result.data;
    },
  });
  function close() {
    if (!busy) {
      setOpen(false);
      setBlocked(false);
      setError("");
      client.removeQueries({ queryKey: ["school-details", id], exact: true });
    }
  }
  async function save(values: { name: string; active: boolean }) {
    if (sending.current || blocked || !info.data) return;
    sending.current = true;
    setBusy(true);
    setError("");
    let sent = false;
    try {
      const token = await csrf();
      sent = true;
      const { response } = await api.POST(
        "/api/organization/schools/{id}/update",
        {
          params: { path: { id } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: info.data.version, ...values },
        },
      );
      if (!response.ok) {
        if (
          response.status === 403 ||
          response.status === 409 ||
          response.status >= 500 ||
          response.status === 408
        )
          setBlocked(true);
        setError(
          response.status === 409
            ? "学校已被修改，请关闭后重新读取。"
            : response.status === 403
              ? "当前权限不足。"
              : response.status >= 500
                ? "结果尚未确认，请关闭后重新核对。"
                : "保存失败，请核对学校名称。",
        );
        return;
      }
      setOpen(false);
      client.removeQueries({ queryKey: ["school-details", id], exact: true });
      onSaved();
    } catch {
      if (sent) setBlocked(true);
      setError(
        sent
          ? "结果尚未确认，请关闭后重新核对。"
          : "无法获取安全令牌，请重试。",
      );
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true);
          setError("");
          void info.refetch();
        }}
      >
        管理学校
      </Button>
      <Modal
        title="管理学校"
        open={open}
        onCancel={close}
        closable={!busy}
        mask={{ closable: !busy }}
        footer={null}
        destroyOnHidden
      >
        {info.isFetching ? (
          <p>正在读取…</p>
        ) : info.error ? (
          <Alert type="error" title={info.error.message} />
        ) : info.data ? (
          <>
            <p>停用后，该校历史资料仍可查看，但不能新增或修改；可再次启用。</p>
            {error ? <Alert type="error" title={error} /> : null}
            <Form
              key={info.data.version}
              layout="vertical"
              initialValues={{ name: info.data.name, active: info.data.active }}
              onFinish={save}
              disabled={busy || blocked}
            >
              <Form.Item
                name="name"
                label="学校名称"
                rules={[{ required: true, whitespace: true }]}
              >
                <Input maxLength={100} />
              </Form.Item>
              <Form.Item name="active" label="学校启用" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Space>
                <Button type="primary" htmlType="submit" loading={busy}>
                  保存
                </Button>
                <Button onClick={close}>关闭</Button>
              </Space>
            </Form>
          </>
        ) : null}
      </Modal>
    </>
  );
}
