import { useState } from "react";
import { signOut, type PlatformOperator } from "./api";
import AuditPage from "./AuditPage";
import OverviewPage from "./OverviewPage";
import ProvisioningPage from "./ProvisioningPage";
import TenantList from "./TenantList";
import { currentEnvironment } from "../shared/env";
import styles from "./platform.module.css";

/** 平台内的四个页面；套餐与配额、平台账号仍是后续批次，保留占位不假装可点。 */
type PageKey = "overview" | "tenants" | "provisioning" | "audit";

const OPERATIONS: ReadonlyArray<{ key: PageKey; label: string }> = [
  { key: "overview", label: "总览" },
  { key: "tenants", label: "租户管理" },
  { key: "provisioning", label: "开通记录" },
];

const TITLES: Record<PageKey, string> = {
  overview: "总览",
  tenants: "租户管理",
  provisioning: "开通记录",
  audit: "审计日志",
};

/** 平台端外壳：固定主题、固定导航；租户品牌不会进入这里。 */
export default function PlatformConsole({
  operator,
  onSessionLost,
}: {
  operator: PlatformOperator;
  onSessionLost: () => void;
}) {
  const [leaving, setLeaving] = useState(false);
  // 默认仍落在租户管理：日常运营最常做的是找租户、开通、停用，总览是补充视角。
  const [page, setPage] = useState<PageKey>("tenants");
  const env = currentEnvironment();
  const envClass =
    env.tone === "production"
      ? styles.envProduction
      : env.tone === "preview"
        ? styles.envPreview
        : "";

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
          {OPERATIONS.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`${styles.navItem} ${styles.navAction}`}
              aria-current={page === item.key ? "page" : undefined}
              onClick={() => setPage(item.key)}
            >
              {item.label}
            </button>
          ))}
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            套餐与配额 <span className={styles.soon}>即将开放</span>
          </span>
        </nav>
        <div className={styles.navGroup}>治理</div>
        <nav className={styles.nav} aria-label="治理导航">
          <button
            type="button"
            className={`${styles.navItem} ${styles.navAction}`}
            aria-current={page === "audit" ? "page" : undefined}
            onClick={() => setPage("audit")}
          >
            审计日志
          </button>
          <span
            className={`${styles.navItem} ${styles.navDisabled}`}
            aria-disabled="true"
          >
            平台账号 <span className={styles.soon}>即将开放</span>
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
            平台运营 / <b>{TITLES[page]}</b>
          </div>
          <div className={styles.topActions}>
            <span className={`${styles.env} ${envClass}`}>{env.label}</span>
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
          {page === "overview" ? (
            <OverviewPage
              onSessionLost={onSessionLost}
              onOpen={(target) => setPage(target)}
            />
          ) : page === "provisioning" ? (
            <ProvisioningPage onSessionLost={onSessionLost} />
          ) : page === "audit" ? (
            <AuditPage onSessionLost={onSessionLost} />
          ) : (
            <TenantList
              actions={operator.actions}
              onSessionLost={onSessionLost}
            />
          )}
        </div>
      </main>
    </div>
  );
}
