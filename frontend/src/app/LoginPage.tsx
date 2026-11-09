import { useState } from "react";
import { Button, Form, Input } from "antd";
import { signIn } from "./session";
import styles from "./auth.module.css";

interface Credentials {
  username: string;
  password: string;
}

/**
 * 租户端登录页：与平台运营入口完全分开，不显示平台标识或平台账号提示。
 *
 * 无域名环境先使用中性主题；登录成功后由 TenantBrandingProvider 应用机构品牌。
 */
export function LoginPage({
  onSignedIn,
  notice,
}: {
  onSignedIn: () => void;
  notice?: string;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(values: Credentials) {
    setSubmitting(true);
    setError("");
    try {
      await signIn({
        username: values.username.trim(),
        password: values.password,
      });
      onSignedIn();
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : "登录失败，请稍后重试",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.brand}>
          <span className={styles.mark}>T</span>
          <span className={styles.brandText}>
            <b>TeraSchool</b>
            <em>教务管理端</em>
          </span>
        </div>
        <h1 className={styles.title}>登录</h1>
        <p className={styles.hint}>
          使用机构管理员或员工账号登录。账号由机构管理员创建，忘记密码请联系机构管理员。
        </p>
        {notice ? (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        ) : null}
        {error ? (
          <p className={styles.error} role="alert">
            {error}
          </p>
        ) : null}
        <Form<Credentials>
          layout="vertical"
          requiredMark={false}
          onFinish={(values) => void submit(values)}
        >
          <Form.Item
            name="username"
            label="登录名"
            rules={[{ required: true, message: "请输入登录名" }]}
          >
            <Input autoComplete="username" autoFocus size="large" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: "请输入密码" }]}
          >
            <Input.Password autoComplete="current-password" size="large" />
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
        <p className={styles.foot}>TeraSchool · 教务管理端</p>
      </div>
    </div>
  );
}
