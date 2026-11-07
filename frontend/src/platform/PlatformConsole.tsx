import { useState } from "react";
import { signOut, type PlatformOperator } from "./api";
import TenantList from "./TenantList";
import styles from "./platform.module.css";

/** 平台端外壳：固定主题、固定导航；租户品牌不会进入这里。 */
export default function PlatformConsole({
  operator,
  onSessionLost,
}: {
  operator: PlatformOperator;
  onSessionLost: () => void;
}) {
  const [leaving, setLeaving] = useState(false);

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
        <nav className={styles.nav} aria-label="平台导航">
          <span className={styles.navItem} aria-current="page">
            租户管理
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
          <span className={styles.crumb}>{operator.login}</span>
        </header>
        <div className={styles.content}>
          <TenantList
            canCreate={operator.actions.includes("TENANT_CREATE")}
            onSessionLost={onSessionLost}
          />
        </div>
      </main>
    </div>
  );
}
