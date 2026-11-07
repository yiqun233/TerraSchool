import { useState } from "react";
import { Alert, Button, Form, Input, Space, Typography } from "antd";
import { changePassword, SessionExpired } from "../../shared/http";

/**
 * 首次登录强制改密。
 *
 * 平台开通或重置密码后，账号处于「必须改密」状态：除会话自身接口外一律被服务端拒绝。
 * 这里不做任何权限判断，只负责把当前密码换成新密码并把人送回登录页——
 * 改密会递增凭据版本，当前会话必然失效，假装还能继续用只会让人困惑。
 */
export function ChangePasswordGate({
  className,
  variant = "page",
  onChanged,
  onSessionLost,
}: {
  className?: string;
  /** page：占满内容区（首次强制改密）；modal：放进对话框（主动改密）。 */
  variant?: "page" | "modal";
  onChanged: () => void;
  onSessionLost: () => void;
}) {
  const [form] = Form.useForm<{
    currentPassword: string;
    newPassword: string;
    confirmPassword: string;
  }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(values: {
    currentPassword: string;
    newPassword: string;
  }) {
    setBusy(true);
    setError("");
    try {
      await changePassword(values.currentPassword, values.newPassword);
      onChanged();
    } catch (failure) {
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      setError(failure instanceof Error ? failure.message : "修改失败，请重试");
    } finally {
      setBusy(false);
    }
  }

  const body = (
    <>
      <p>
        {variant === "page"
          ? "这是账号首次登录或被管理员重置后的第一次登录，必须先修改密码。"
          : "修改后其它设备上的登录会立即失效，需要重新登录。"}
        新密码至少 12 位，不能与登录名或当前密码相同，也不能纯为数字或字母。
      </p>
      {error ? <Alert type="error" showIcon title={error} /> : null}
      <Form form={form} layout="vertical" onFinish={submit} disabled={busy}>
        <Form.Item
          name="currentPassword"
          label="当前密码"
          rules={[{ required: true, message: "请输入当前密码" }]}
        >
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[
            { required: true, message: "请输入新密码" },
            { min: 12, message: "至少 12 位" },
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="确认新密码"
          dependencies={["newPassword"]}
          rules={[
            { required: true, message: "请再次输入新密码" },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue("newPassword") === value)
                  return Promise.resolve();
                return Promise.reject(new Error("两次输入的新密码不一致"));
              },
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" loading={busy}>
            保存新密码
          </Button>
          <Typography.Text type="secondary">
            保存后需要用它重新登录。
          </Typography.Text>
        </Space>
      </Form>
    </>
  );

  if (variant === "modal") return body;
  return (
    <section className={className} aria-label="首次登录修改密码">
      <Typography.Title level={3}>请先设置新密码</Typography.Title>
      {body}
    </section>
  );
}
