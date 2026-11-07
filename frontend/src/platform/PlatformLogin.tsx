import { useState } from "react";
import { Button, Form, Input } from "antd";
import { LoginRejected, RequestFailed, signIn } from "./api";
import styles from "./platform.module.css";

interface Credentials {
  login: string;
  password: string;
}

/** 平台登录：与租户登录完全分开，避免两套身份混用。 */
export default function PlatformLogin({
  onSignedIn,
}: {
  onSignedIn: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(values: Credentials) {
    setSubmitting(true);
    setError(null);
    try {
      await signIn(values.login.trim(), values.password);
      onSignedIn();
    } catch (failure) {
      if (failure instanceof LoginRejected) {
        setError(failure.message);
      } else if (failure instanceof RequestFailed && failure.status === 429) {
        setError("尝试过于频繁，请稍后再试");
      } else {
        setError(
          failure instanceof Error ? failure.message : "登录失败，请稍后重试",
        );
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.loginPage}>
      <div className={styles.loginCard}>
        <div className={styles.loginBrand}>
          <span className={styles.mark}>T</span>
          <span className={styles.brandText}>
            <b>平台运营</b>
            <em>TeraSchool</em>
          </span>
        </div>
        <h1 className={styles.loginTitle}>登录平台运营端</h1>
        <p className={styles.loginHint}>
          平台账号与租户账号相互独立，租户账号无法登录此入口。
        </p>
        {error ? (
          <p className={styles.loginError} role="alert">
            {error}
          </p>
        ) : null}
        <Form<Credentials>
          layout="vertical"
          requiredMark={false}
          onFinish={(values) => void submit(values)}
        >
          <Form.Item
            name="login"
            label="登录名"
            rules={[{ required: true, message: "请输入平台登录名" }]}
          >
            <Input
              autoComplete="username"
              autoFocus
              placeholder="平台登录名"
              size="large"
            />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: "请输入密码" }]}
          >
            <Input.Password
              autoComplete="current-password"
              placeholder="密码"
              size="large"
            />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            block
            size="large"
            loading={submitting}
          >
            登录
          </Button>
        </Form>
        <p className={styles.loginFoot}>仅限平台运营人员使用</p>
      </div>
    </div>
  );
}
