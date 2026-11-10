import { useState } from "react";
import { Alert, App as AntApp, Button, Form, Input, Modal } from "antd";
import {
  AccessDenied,
  RequestFailed,
  SessionExpired,
  provisionTenant,
} from "./api";
import styles from "./platform.module.css";
import { newRequestId } from "../shared/ids";

/** 生成 16 位口令：去掉容易看错的 0/O/1/l/I。 */
function generatePassword() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  const bytes = new Uint32Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (value) => alphabet[value % alphabet.length]).join(
    "",
  );
}

/**
 * 开通向导：两步。
 *
 * 第一步只填机构名称与管理员登录名；初始口令由前端生成并随请求提交，
 * 平台重置密码时才由服务端生成。第二步展示一次性的初始密码，离开后只能重置。
 */
export default function ProvisionWizard({
  open,
  canCreate,
  onClose,
  onCreated,
  onSessionLost,
}: {
  open: boolean;
  canCreate: boolean;
  onClose: () => void;
  onCreated: (name: string) => void;
  onSessionLost: () => void;
}) {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<{ name: string; adminLogin: string }>();
  const [requestId, setRequestId] = useState(() => newRequestId());
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [errorText, setErrorText] = useState("");
  const [done, setDone] = useState<{ name: string; login: string } | null>(
    null,
  );

  function reset() {
    form.resetFields();
    setRequestId(newRequestId());
    setPassword("");
    setErrorText("");
    setDone(null);
    setBusy(false);
  }

  async function copyCredential() {
    if (!done) return;
    try {
      await navigator.clipboard.writeText(
        `登录名：${done.login}\n初始密码：${password}`,
      );
      message.success("登录信息已复制");
    } catch {
      message.error("复制失败，请手动选择登录名和初始密码");
    }
  }

  async function submit() {
    const values = await form.validateFields();
    const initial = password || generatePassword();
    setPassword(initial);
    setBusy(true);
    setErrorText("");
    try {
      await provisionTenant({
        requestId,
        name: values.name.trim(),
        adminLogin: values.adminLogin.trim(),
        adminPassword: initial,
      });
      setDone({ name: values.name.trim(), login: values.adminLogin.trim() });
      onCreated(values.name.trim());
    } catch (failure) {
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      if (failure instanceof AccessDenied) {
        setErrorText("当前账号没有开通租户的权限（TENANT_CREATE）。");
        return;
      }
      setErrorText(
        failure instanceof RequestFailed ? failure.message : "开通失败，请重试",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title={done ? "开通完成" : "开通新租户"}
      width={520}
      closable={!busy}
      maskClosable={!busy}
      footer={null}
      onCancel={() => {
        if (busy) return;
        reset();
        onClose();
      }}
    >
      {done ? (
        <div className={styles.result}>
          <p className={styles.stateTitle}>「{done.name}」已开通</p>
          <p className={styles.stateHint}>
            把下面的登录信息交给客户。初始密码只显示这一次，离开后无法再次查看；
            忘记只能重置，客户首次登录会被要求改密。
          </p>
          <dl className={styles.credential}>
            <dt>登录名</dt>
            <dd>{done.login}</dd>
            <dt>初始密码</dt>
            <dd>
              <code>{password}</code>
            </dd>
          </dl>
          <Button onClick={() => void copyCredential()}>复制登录信息</Button>
          <Button
            type="primary"
            style={{ marginLeft: 8 }}
            onClick={() => {
              reset();
              onClose();
            }}
          >
            我已保存，关闭
          </Button>
        </div>
      ) : (
        <>
          {!canCreate ? (
            <Alert
              type="warning"
              showIcon
              title="当前账号没有开通租户的权限（TENANT_CREATE）。"
            />
          ) : null}
          {errorText ? (
            <Alert
              type="error"
              showIcon
              title={errorText}
              style={{ marginBottom: 12 }}
            />
          ) : null}
          <Form
            form={form}
            layout="vertical"
            disabled={!canCreate || busy}
            onValuesChange={() => setRequestId(newRequestId())}
          >
            <Form.Item
              name="name"
              label="机构名称"
              rules={[
                { required: true, message: "请填写机构名称" },
                { max: 100, message: "最多 100 个字符" },
              ]}
            >
              <Input placeholder="例如：启明教育培训学校" />
            </Form.Item>
            <Form.Item
              name="adminLogin"
              label="管理员登录名"
              rules={[
                { required: true, message: "请填写管理员登录名" },
                { min: 3, message: "至少 3 个字符" },
                { max: 60, message: "最多 60 个字符" },
              ]}
              extra="全平台唯一；客户首次登录会被要求设置新密码。"
            >
              <Input placeholder="例如：qiming-admin" />
            </Form.Item>
            <p className={styles.stateHint}>
              学校不在这里创建：客户登录后自行建校、校区与部门。
            </p>
          </Form>
          <div className={styles.modalActions}>
            <Button onClick={onClose} disabled={busy}>
              取消
            </Button>
            <Button
              type="primary"
              loading={busy}
              disabled={!canCreate}
              onClick={() => void submit()}
            >
              开通
            </Button>
          </div>
        </>
      )}
    </Modal>
  );
}
