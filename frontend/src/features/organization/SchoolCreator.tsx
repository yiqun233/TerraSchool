import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Form, Input, Modal } from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import { newRequestId } from "../../shared/ids";
type CreateSchool = components["schemas"]["CreateSchool"];
export function SchoolCreator({ onClosed }: { onClosed: () => void }) {
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [locked, setLocked] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const [form] = Form.useForm<{ name: string }>();
  const inFlight = useRef(false),
    pending = useRef<CreateSchool | null>(null);
  const capability = useQuery({
    queryKey: ["school-create-capability"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/schools/capabilities",
        { signal },
      );
      if (!response.ok || !data) throw new Error("无法读取学校创建权限");
      return data;
    },
  });
  if (!capability.data?.canCreate || capability.error) return null;
  function close() {
    if (!busy && !locked) {
      setOpen(false);
      setBlocked(false);
      form.resetFields();
      pending.current = null;
      onClosed();
    }
  }
  async function save(values: { name: string }) {
    if (inFlight.current || blocked) return;
    inFlight.current = true;
    setBusy(true);
    setError("");
    let sent = false;
    try {
      if (!pending.current)
        pending.current = { requestId: newRequestId(), name: values.name };
      const token = await csrf();
      sent = true;
      const { response } = await api.POST("/api/organization/schools", {
        headers: { [token.headerName!]: token.token! },
        body: pending.current,
      });
      if (!response.ok) {
        if (response.status >= 500 || response.status === 408) {
          setLocked(true);
          setError("结果尚未确认，请使用相同请求重试。");
        } else if (locked) {
          setLocked(false);
          setBlocked(true);
          setError(
            "之前的创建结果仍未确认，请关闭后先核对学校目录，勿直接重复创建。",
          );
        } else {
          pending.current = null;
          setLocked(false);
          setError("学校创建失败，请核对权限和名称。");
        }
        return;
      }
      setLocked(false);
      pending.current = null;
      setOpen(false);
      form.resetFields();
      onClosed();
    } catch {
      if (sent) setLocked(true);
      else if (!locked) pending.current = null;
      setError(
        sent
          ? "结果尚未确认，请使用相同请求重试。"
          : "无法获取安全令牌，请重试。",
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <Button
        onClick={() => {
          setError("");
          setOpen(true);
        }}
      >
        开通学校
      </Button>
      <Modal
        title="开通学校"
        open={open}
        onCancel={close}
        closable={!busy && !locked}
        mask={{ closable: !busy && !locked }}
        keyboard={!busy && !locked}
        footer={null}
      >
        <p>创建后，当前管理员获得新学校的管理和基础资源权限。</p>
        {error ? <Alert title={error} type="error" /> : null}
        <Form form={form} layout="vertical" onFinish={save}>
          <Form.Item
            label="学校名称"
            name="name"
            rules={[
              { required: true, whitespace: true, message: "请输入学校名称" },
            ]}
          >
            <Input maxLength={100} disabled={busy || locked || blocked} />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={busy}
            disabled={blocked}
          >
            {locked ? "重试相同请求" : "创建学校"}
          </Button>
        </Form>
      </Modal>
    </>
  );
}
