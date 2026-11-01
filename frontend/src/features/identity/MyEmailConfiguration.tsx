import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Form, Input, Modal, Select, Space } from "antd";
import { api, csrf } from "../../shared/http";

type Draft = {
  smtpHost: string;
  smtpUser: string;
  senderAddress: string;
  credentialAction: "KEEP" | "REPLACE" | "CLEAR";
  credential?: string;
};
export function MyEmailConfiguration({ onChanged }: { onChanged: () => void }) {
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const [form] = Form.useForm<Draft>();
  const action = Form.useWatch("credentialAction", form);
  const sending = useRef(false),
    client = useQueryClient();
  const config = useQuery({
    queryKey: ["my-email-configuration"],
    enabled: open,
    retry: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const result = await api.GET("/api/employee/me/email-config", { signal });
      if (!result.response.ok || !result.data)
        throw new Error("当前账号未关联员工，或暂时无法读取邮箱配置。");
      return result.data;
    },
  });
  function close() {
    if (!busy) {
      setOpen(false);
      setBlocked(false);
      setError("");
      form.resetFields();
      client.removeQueries({
        queryKey: ["my-email-configuration"],
        exact: true,
      });
    }
  }
  async function save(values: Draft) {
    if (sending.current || blocked || !config.data) return;
    sending.current = true;
    setBusy(true);
    setError("");
    let sent = false;
    try {
      const token = await csrf();
      sent = true;
      const { response, error: problem } = await api.POST(
        "/api/employee/me/email-config",
        {
          headers: { [token.headerName!]: token.token! },
          body: {
            expectedVersion: config.data.version,
            smtpHost: values.smtpHost ?? "",
            smtpUser: values.smtpUser ?? "",
            senderAddress: values.senderAddress ?? "",
            credentialAction: values.credentialAction,
            credential:
              values.credentialAction === "REPLACE"
                ? values.credential
                : undefined,
          },
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
        const code = (problem as { code?: string } | undefined)?.code ?? "";
        setError(
          code === "MAIL_KEY_MISMATCH" || code === "MAIL_KEY_UNAVAILABLE"
            ? "邮件凭据密钥不可用，请联系管理员恢复原密钥。"
            : response.status === 409
              ? "资料版本已变化，请关闭后重新读取。"
              : response.status === 403
                ? "当前账号未关联员工或学校已停用。"
                : response.status >= 500
                  ? "保存结果未确认，请关闭后重新核对。"
                  : "保存失败，请核对输入。",
        );
        return;
      }
      setOpen(false);
      form.resetFields();
      client.removeQueries({
        queryKey: ["my-email-configuration"],
        exact: true,
      });
      onChanged();
    } catch {
      if (sent) setBlocked(true);
      setError(
        sent
          ? "保存结果未确认，请关闭后重新核对。"
          : "无法获取安全令牌，请重新输入密码后重试。",
      );
    } finally {
      form.setFieldValue("credential", undefined);
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
          void config.refetch();
        }}
      >
        我的邮箱配置
      </Button>
      <Modal
        title="我的邮箱配置"
        open={open}
        onCancel={close}
        closable={!busy}
        mask={{ closable: !busy }}
        footer={null}
        destroyOnHidden
      >
        {config.isFetching ? (
          <p>正在读取…</p>
        ) : config.error ? (
          <Alert type="error" title={config.error.message} />
        ) : config.data ? (
          <>
            <p>
              只维护当前员工的邮箱连接信息；密码不会显示。本页面不会发送邮件。
            </p>
            <p>当前凭据：{config.data.hasCredential ? "已设置" : "未设置"}</p>
            {error ? <Alert type="error" title={error} /> : null}
            <Form
              form={form}
              key={config.data.version}
              layout="vertical"
              initialValues={{
                smtpHost: config.data.smtpHost ?? "",
                smtpUser: config.data.smtpUser ?? "",
                senderAddress: config.data.senderAddress ?? "",
                credentialAction: "KEEP",
              }}
              onFinish={save}
              disabled={busy || blocked}
            >
              <Form.Item name="smtpHost" label="SMTP 地址">
                <Input maxLength={200} />
              </Form.Item>
              <Form.Item name="smtpUser" label="SMTP 用户名">
                <Input maxLength={200} />
              </Form.Item>
              <Form.Item name="senderAddress" label="发件邮箱">
                <Input maxLength={200} />
              </Form.Item>
              <Form.Item name="credentialAction" label="邮箱凭据">
                <Select
                  options={[
                    { value: "KEEP", label: "保留原凭据" },
                    { value: "REPLACE", label: "设置或更换凭据" },
                    { value: "CLEAR", label: "清除凭据" },
                  ]}
                  onChange={() => form.setFieldValue("credential", undefined)}
                />
              </Form.Item>
              {action === "REPLACE" ? (
                <Form.Item
                  name="credential"
                  label="新邮箱密码或授权码"
                  rules={[{ required: true, message: "请输入密码或授权码" }]}
                >
                  <Input.Password
                    autoComplete="new-password"
                    maxLength={1024}
                  />
                </Form.Item>
              ) : null}
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
