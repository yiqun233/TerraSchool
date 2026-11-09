import { useState } from "react";
import { signOut, type PlatformOperator } from "./api";
import TenantList from "./TenantList";
import styles from "./platform.module.css";

function environment() {
  const value =
    (import.meta.env.VITE_APP_ENV as string | undefined) ??
    import.meta.env.MODE;
  if (value === "production")
    return { label: "生产环境", className: styles.envProduction };
  if (value === "preview" || value === "staging")
    return { label: "预览环境", className: styles.envPreview };
  return { label: "本地开发", className: "" };
}

/** 平台端外壳：固定主题、固定导航；租户品牌不会进入这里。 */
export default function PlatformConsole({
  operator,
  onSessionLost,
}: {
  operator: PlatformOperator;
  onSessionLost: () => void;
}) {
  const [leaving, setLeaving] = useState(false);
  const env = environment();

  async function leave() {
    setLeaving(true);
    try {
      await signOut();
    } catch {
      // 退出失败也清空本地会话状态，避免用户卡在控制台里。
    } finally {
      setLeaving(false);
      onSessionLost();
    }
  }

  return (
    <div className={styles.shell}>
      <aside className={styles.side}>
        <div className={styles.brand}>
          <span className={styles.mark}>T</span>
          <span className={styles.brandText}>
            <b>平台运营</b>
            <em>TeraSchool</em>
          </span>
        </div>
        <div className={styles.navGroup}>运营</div>
        <nav className={styles.nav} aria-label="运营导航">
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            总览 <span className={styles.soon}>即将开放</span>
          </span>
          <span className={styles.navItem} aria-current="page">
            租户管理
          </span>
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            开通记录 <span className={styles.soon}>即将开放</span>
          </span>
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            套餐与配额 <span className={styles.soon}>即将开放</span>
          </span>
        </nav>
        <div className={styles.navGroup}>治理</div>
        <nav className={styles.nav} aria-label="治理导航">
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            平台账号 <span className={styles.soon}>即将开放</span>
          </span>
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            审计日志 <span className={styles.soon}>即将开放</span>
          </span>
        </nav>
        <div className={styles.sideFoot}>
          <div className={styles.avatar} aria-hidden="true">
            {(operator.displayName || operator.login).slice(0, 1)}
          </div>
          <div className={styles.who}>
            <b>{operator.displayName}</b>
            <em>平台运营</em>
          </div>
          <button
            type="button"
            className={styles.relogin}
            onClick={() => void leave()}
            disabled={leaving}
          >
            退出
          </button>
        </div>
      </aside>

      <main className={styles.main}>
        <header className={styles.topbar}>
          <div className={styles.crumb}>
            平台运营 / <b>租户管理</b>
          </div>
          <div className={styles.topActions}>
            <span className={`${styles.env} ${env.className}`}>
              {env.label}
            </span>
            <button
              type="button"
              className={styles.textAction}
              title="帮助将在后续版本开放"
              disabled
            >
              帮助
            </button>
            <button
              type="button"
              className={styles.textAction}
              title="通知将在后续版本开放"
              disabled
            >
              通知
            </button>
            <button
              type="button"
              className={styles.textAction}
              title="AI 助手将在后续版本开放"
              disabled
            >
              AI 助手
            </button>
            <span className={styles.crumb}>{operator.login}</span>
          </div>
        </header>
        <div className={styles.content}>
          <TenantList
            actions={operator.actions}
            onSessionLost={onSessionLost}
          />
        </div>
      </main>
    </div>
  );
}
