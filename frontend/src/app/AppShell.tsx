import { Suspense, useEffect, useState } from "react";
import { App as AntApp, Avatar, Button, Dropdown, Modal, Tooltip } from "antd";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { ChangePasswordGate } from "../features/identity/ChangePasswordGate";
import { useTenantBranding } from "../shared/theme/TenantBrandingProvider";
import { LoadingBlock } from "../shared/ui/Page";
import { findNav, TENANT_GROUPS, visibleNavigation } from "./navigation";
import { endSession, signOut, useSession } from "./session";
import styles from "./app.module.css";

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

/**
 * 租户端外壳：只负责身份、导航、顶栏与内容区。
 *
 * 页面不自己画侧栏、顶栏或登录入口；权限显隐只改善体验，接口仍会再校验。
 */
export function AppShell() {
  const { session, permissions, onUnauthorized } = useSession();
  const { branding } = useTenantBranding();
  const { message } = AntApp.useApp();
  const client = useQueryClient();
  const location = useLocation();
  const navigate = useNavigate();
  const [navOpen, setNavOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const env = environment();
  const current = findNav(location.pathname);
  const displayName = branding.displayName || session.tenantName;
  const markText =
    (branding.displayName || session.tenantName || "T").trim().slice(0, 1) ||
    "T";

  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    document.title = current
      ? `${current.title} · ${displayName}`
      : `${displayName} · TeraSchool`;
  }, [current, displayName]);

  // favicon 跟随租户方形 Logo；没有 Logo 时保留浏览器默认，不伪造第二套品牌。
  useEffect(() => {
    const href = branding.logoMarkUrl;
    if (!href) return;
    let link = document.querySelector<HTMLLinkElement>("link[rel~='icon']");
    const created = link === null;
    if (!link) {
      link = document.createElement("link");
      link.rel = "icon";
      document.head.appendChild(link);
    }
    const previous = link.getAttribute("href");
    link.setAttribute("href", href);
    return () => {
      if (created) link.remove();
      else if (previous) link.setAttribute("href", previous);
      else link.removeAttribute("href");
    };
  }, [branding.logoMarkUrl]);

  async function leave() {
    setLeaving(true);
    try {
      await signOut(session.tenantId);
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : "退出失败，请重试",
      );
    } finally {
      // 服务端退出成功与否都要回登录页：本地会话留着只会让人卡在已失效的壳层里。
      endSession(client, session.tenantId);
      setLeaving(false);
    }
  }

  function sessionLost() {
    onUnauthorized();
  }

  const items = visibleNavigation(permissions, session.tenantAdmin);
  const groups = TENANT_GROUPS.map((group) => ({
    group,
    items: items.filter((item) => item.group === group),
  })).filter((section) => section.items.length > 0);

  return (
    <div className={styles.shell}>
      {navOpen ? (
        <div
          className={styles.scrim}
          role="presentation"
          onClick={() => setNavOpen(false)}
        />
      ) : null}
      <aside className={`${styles.side} ${navOpen ? styles.sideOpen : ""}`}>
        <div className={styles.brand}>
          {branding.logoWideUrl ? (
            <img
              className={styles.logoWide}
              src={branding.logoWideUrl}
              alt={displayName}
            />
          ) : (
            <>
              <span className={styles.mark}>
                {branding.logoMarkUrl ? (
                  <img src={branding.logoMarkUrl} alt="" />
                ) : (
                  markText
                )}
              </span>
              <span className={styles.brandText}>
                <b title={displayName}>{displayName}</b>
                <em>教务管理端</em>
              </span>
            </>
          )}
        </div>
        <div className={styles.navScroll}>
          {groups.map((section) => (
            <div key={section.group}>
              <div className={styles.navGroup}>{section.group}</div>
              <nav className={styles.nav} aria-label={`${section.group}导航`}>
                {section.items.map((item) =>
                  item.enabled ? (
                    <NavLink
                      key={item.path}
                      to={item.path}
                      className={({ isActive }) =>
                        `${styles.navItem} ${isActive ? styles.navItemActive : ""}`
                      }
                    >
                      <span>{item.label}</span>
                    </NavLink>
                  ) : (
                    <span
                      key={item.path}
                      className={`${styles.navItem} ${styles.navDisabled}`}
                      aria-disabled="true"
                      title="即将开放"
                    >
                      <span>{item.label}</span>
                      <span className={styles.soon}>即将开放</span>
                    </span>
                  ),
                )}
              </nav>
            </div>
          ))}
        </div>
        <div className={styles.sideFoot}>
          TeraSchool · 教务管理端
          <br />
          品牌由机构自行设置
        </div>
      </aside>

      <main className={styles.main}>
        <header className={styles.topbar}>
          <div className={styles.crumb}>
            <Button
              type="text"
              size="small"
              className={styles.navToggle}
              aria-label="打开导航"
              onClick={() => setNavOpen((open) => !open)}
            >
              菜单
            </Button>
            {current ? (
              <>
                <span>{current.group}</span>
                <i>/</i>
                <b>{current.title}</b>
              </>
            ) : (
              <b>TeraSchool</b>
            )}
          </div>
          <div className={styles.topActions}>
            <span
              className={`${styles.env} ${env.className}`}
              title="当前运行环境"
            >
              {env.label}
            </span>
            <Tooltip title="帮助将在后续版本开放">
              <Button type="text" size="small" disabled>
                帮助
              </Button>
            </Tooltip>
            <Tooltip title="通知将在后续版本开放">
              <Button type="text" size="small" disabled>
                通知
              </Button>
            </Tooltip>
            <Tooltip title="AI 助手将在后续版本开放">
              <Button type="text" size="small" disabled>
                AI 助手
              </Button>
            </Tooltip>
            <Button
              type="text"
              size="small"
              onClick={() => navigate("/permissions")}
            >
              我的权限
            </Button>
            <Dropdown
              trigger={["click"]}
              menu={{
                items: [
                  { key: "password", label: "修改密码" },
                  { type: "divider" },
                  {
                    key: "logout",
                    label: leaving ? "正在退出…" : "退出登录",
                    disabled: leaving,
                  },
                ],
                onClick: ({ key }) => {
                  if (key === "password") setPasswordOpen(true);
                  if (key === "logout") void leave();
                },
              }}
            >
              <button
                type="button"
                className={styles.accountButton}
                aria-label="账号菜单"
              >
                <Avatar
                  size={26}
                  style={{
                    background: "var(--ts-brand-soft)",
                    color: "var(--ts-brand)",
                  }}
                >
                  {(session.displayName || session.login).slice(0, 1)}
                </Avatar>
                <span className={styles.accountName}>
                  {session.displayName || session.login}
                </span>
              </button>
            </Dropdown>
          </div>
        </header>
        <div className={styles.content}>
          <Suspense fallback={<LoadingBlock>正在加载页面…</LoadingBlock>}>
            <Outlet />
          </Suspense>
        </div>
      </main>

      <Modal
        open={passwordOpen}
        title="修改密码"
        footer={null}
        onCancel={() => setPasswordOpen(false)}
        destroyOnHidden
      >
        <ChangePasswordGate
          variant="modal"
          onChanged={() => {
            setPasswordOpen(false);
            message.success("密码已更新，请用新密码重新登录。");
            sessionLost();
          }}
          onSessionLost={() => {
            setPasswordOpen(false);
            sessionLost();
          }}
        />
      </Modal>
    </div>
  );
}

/** 启动期只显示最小骨架，避免先闪一下空表格。 */
export function ShellBoot({ label = "正在确认会话…" }: { label?: string }) {
  return (
    <div className={styles.boot}>
      <div className={styles.bootCard}>
        <p>{label}</p>
      </div>
    </div>
  );
}
