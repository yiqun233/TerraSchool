import { useCallback, useEffect, useState } from "react";
import { App, Button, ConfigProvider, Spin } from "antd";
import zhCN from "antd/locale/zh_CN";
import { currentOperator, type PlatformOperator } from "./api";
import { platformTheme } from "./theme";
import PlatformLogin from "./PlatformLogin";
import PlatformConsole from "./PlatformConsole";
import styles from "./platform.module.css";

/**
 * 平台运营端根组件：先确认会话，再决定登录还是进入控制台。
 * 这里不引入租户主题，平台外观只由 platformTheme 决定（DEC-025）。
 */
export default function PlatformApp() {
  const [operator, setOperator] = useState<PlatformOperator | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setFailure(null);
    try {
      setOperator(await currentOperator());
    } catch (error) {
      setFailure(error instanceof Error ? error.message : "无法连接服务");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 稳定回调：租户列表以它为依赖，避免每次渲染都重新请求。
  const sessionLost = useCallback(() => setOperator(null), []);

  return (
    <ConfigProvider
      locale={zhCN}
      theme={platformTheme}
      button={{ autoInsertSpace: false }}
    >
      <App>
        {loading ? (
          <div className={styles.loginPage}>
            <div className={styles.boot}>
              <Spin />
              <p className={styles.stateHint}>正在确认会话…</p>
            </div>
          </div>
        ) : failure ? (
          <div className={styles.loginPage}>
            <div className={styles.loginCard}>
              <h1 className={styles.loginTitle}>无法进入平台运营端</h1>
              <p className={styles.loginHint}>{failure}</p>
              <Button type="primary" block onClick={() => void load()}>
                重试
              </Button>
            </div>
          </div>
        ) : operator ? (
          <PlatformConsole operator={operator} onSessionLost={sessionLost} />
        ) : (
          <PlatformLogin onSignedIn={() => void load()} />
        )}
      </App>
    </ConfigProvider>
  );
}
