import { useCallback, useEffect, useRef, useState } from "react";
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Input,
  Modal,
  Skeleton,
  Table,
  Tabs,
} from "antd";
import ProvisionWizard from "./ProvisionWizard";
import type { TableColumnsType } from "antd";
import {
  AccessDenied,
  fetchAdministrators,
  fetchBrandingHistory,
  fetchTenantBranding,
  fetchTenantDetail,
  type BrandingHistoryRow,
  type TenantDetail,
  fetchTenants,
  rollbackTenantBranding,
  resetAdminPassword,
  SessionExpired,
  setTenantActive,
  updateTenantBranding,
  type TenantRow,
  type TenantSummary,
} from "./api";
import type { TenantBranding } from "../shared/theme/brand";
import { StatStrip } from "../shared/ui/Page";
import styles from "./platform.module.css";

const PAGE_SIZE = 20;

type Phase = "loading" | "ready" | "denied" | "failed";

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate(),
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 租户列表：只读信息 + 停用/恢复；不含业务数据（DEC-024）。 */
export default function TenantList({
  actions,
  onSessionLost,
}: {
  actions: string[];
  onSessionLost: () => void;
}) {
  const { message } = App.useApp();
  const canCreate = actions.includes("TENANT_CREATE");
  const canSuspend = actions.includes("TENANT_SUSPEND");
  const canResume = actions.includes("TENANT_RESUME");
  // 重置密码目前复用 TENANT_CREATE；拆分独立动作前按同一权限显隐，避免点了才 403。
  const canResetPassword = canCreate;
  const [rows, setRows] = useState<TenantRow[]>([]);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState<TenantSummary | null>(null);
  const [status, setStatus] = useState("");
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [phase, setPhase] = useState<Phase>("loading");
  const [errorText, setErrorText] = useState("");
  const [target, setTarget] = useState<TenantRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [wizardOpen, setWizardOpen] = useState(false);
  const [resetFor, setResetFor] = useState<TenantRow | null>(null);
  const [admins, setAdmins] = useState<
    Array<{ login: string; active: boolean }>
  >([]);
  const [issued, setIssued] = useState<{
    login: string;
    password: string;
  } | null>(null);
  const [resetBusy, setResetBusy] = useState(false);
  const [resettingLogin, setResettingLogin] = useState<string | null>(null);
  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState("");
  const detailRequest = useRef(0);
  const [adminsLoading, setAdminsLoading] = useState(false);
  const [brandingFor, setBrandingFor] = useState<{
    id: string;
    name: string;
  } | null>(null);
  // 品牌弹窗的请求代次：关闭或换租户后，晚到的响应不能再写进表单。
  const brandingRequest = useRef(0);
  const [branding, setBranding] = useState<TenantBranding | null>(null);
  const [brandingName, setBrandingName] = useState("");
  const [brandingSeed, setBrandingSeed] = useState("");
  const [brandingBusy, setBrandingBusy] = useState(false);
  const [brandingError, setBrandingError] = useState("");
  const [history, setHistory] = useState<BrandingHistoryRow[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [rollingBack, setRollingBack] = useState<number | null>(null);

  const load = useCallback(
    async (targetPage: number) => {
      setPhase("loading");
      try {
        const result = await fetchTenants(
          targetPage,
          PAGE_SIZE,
          status,
          keyword,
        );
        setRows(result.items);
        setTotal(result.total);
        setSummary(result.summary);
        setPage(targetPage);
        setPhase("ready");
      } catch (error) {
        if (error instanceof SessionExpired) {
          onSessionLost();
          return;
        }
        if (error instanceof AccessDenied) {
          setPhase("denied");
          return;
        }
        setErrorText(error instanceof Error ? error.message : "加载失败");
        setPhase("failed");
      }
    },
    [onSessionLost, status, keyword],
  );

  useEffect(() => {
    void load(0);
  }, [load]);

  async function confirmStatusChange() {
    if (!target) return;
    const activating = !target.active;
    setBusy(true);
    try {
      await setTenantActive(target.id, activating);
      message.success(
        activating ? `已恢复「${target.name}」` : `已停用「${target.name}」`,
      );
      setTarget(null);
      await load(page);
    } catch (failure) {
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      message.error(
        failure instanceof Error ? failure.message : "操作失败，请重试",
      );
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(row: TenantRow) {
    const request = ++detailRequest.current;
    setDetail(null);
    setDetailError("");
    setDetailBusy(true);
    try {
      const value = await fetchTenantDetail(row.id);
      // 关闭抽屉会使 request 失效，晚到的响应不能把抽屉重新打开。
      if (request === detailRequest.current) setDetail(value);
    } catch (failure) {
      if (request !== detailRequest.current) return;
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      setDetailError(
        failure instanceof Error ? failure.message : "加载详情失败",
      );
    } finally {
      if (request === detailRequest.current) setDetailBusy(false);
    }
  }

  function closeDetail() {
    detailRequest.current += 1;
    setDetail(null);
    setDetailBusy(false);
    setDetailError("");
  }

  async function openReset(row: TenantRow) {
    setAdminsLoading(true);
    try {
      setAdmins(await fetchAdministrators(row.id));
    } catch (failure) {
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      message.error(
        failure instanceof Error ? failure.message : "加载管理员失败",
      );
      setResetFor(null);
    } finally {
      setAdminsLoading(false);
    }
  }

  async function openBranding(id: string, name: string) {
    // 每次打开都是一代：先关闭 A 再打开 B 时，A 的晚到响应必须作废，
    // 否则表单会显示 A 的配置，而保存用的是 B 的租户与 A 的版本号。
    const request = ++brandingRequest.current;
    setBrandingFor({ id, name });
    setBranding(null);
    setBrandingError("");
    setHistory([]);
    setHistoryLoading(true);
    try {
      const [value, versions] = await Promise.all([
        fetchTenantBranding(id),
        fetchBrandingHistory(id),
      ]);
      if (request !== brandingRequest.current) return;
      setBranding(value);
      setBrandingName(value.displayName ?? "");
      setBrandingSeed(value.seed);
      setHistory(versions);
    } catch (failure) {
      if (request !== brandingRequest.current) return;
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      setBrandingError(
        failure instanceof Error ? failure.message : "加载品牌失败",
      );
    } finally {
      if (request === brandingRequest.current) setHistoryLoading(false);
    }
  }

  function closeBranding() {
    brandingRequest.current += 1;
    setBrandingFor(null);
    setBranding(null);
    setBrandingError("");
    setHistory([]);
    setHistoryLoading(false);
  }

  async function rollbackBranding(version: number) {
    if (!brandingFor) return;
    const request = brandingRequest.current;
    const tenantId = brandingFor.id;
    setRollingBack(version);
    setBrandingError("");
    try {
      const value = await rollbackTenantBranding(tenantId, version);
      if (request !== brandingRequest.current) return;
      const versions = await fetchBrandingHistory(tenantId);
      if (request !== brandingRequest.current) return;
      setBranding(value);
      setBrandingName(value.displayName ?? "");
      setBrandingSeed(value.seed);
      setHistory(versions);
      message.success(`已回滚为版本 ${version} 的内容，并生成新版本`);
    } catch (failure) {
      if (request !== brandingRequest.current) return;
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      setBrandingError(
        failure instanceof Error ? failure.message : "回滚品牌失败",
      );
    } finally {
      if (request === brandingRequest.current) setRollingBack(null);
    }
  }

  async function saveBranding() {
    if (!brandingFor || !branding) return;
    const request = brandingRequest.current;
    const tenantId = brandingFor.id;
    setBrandingBusy(true);
    setBrandingError("");
    try {
      const value = await updateTenantBranding(tenantId, {
        displayName: brandingName.trim() || null,
        seed: brandingSeed,
        version: branding.version,
      });
      if (request !== brandingRequest.current) return;
      setBranding(value);
      setBrandingName(value.displayName ?? "");
      setBrandingSeed(value.seed);
      // 保存会生成新版本；历史列表不刷新就会停在旧版本，和“当前版本”对不上。
      void fetchBrandingHistory(tenantId)
        .then((versions) => {
          if (request === brandingRequest.current) setHistory(versions);
        })
        .catch(() => undefined);
      message.success("品牌已保存并立即生效");
    } catch (failure) {
      if (request !== brandingRequest.current) return;
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      setBrandingError(
        failure instanceof Error ? failure.message : "品牌保存失败",
      );
    } finally {
      if (request === brandingRequest.current) setBrandingBusy(false);
    }
  }

  async function issuePassword(row: TenantRow, login: string) {
    setResetBusy(true);
    setResettingLogin(login);
    try {
      setIssued(await resetAdminPassword(row.id, login));
    } catch (failure) {
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      message.error(failure instanceof Error ? failure.message : "重置失败");
    } finally {
      setResetBusy(false);
      setResettingLogin(null);
    }
  }

  async function copyIssued() {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(
        `登录名：${issued.login}\n初始密码：${issued.password}`,
      );
      message.success("登录信息已复制");
    } catch {
      message.error("复制失败，请手动选择登录名和初始密码");
    }
  }

  const columns: TableColumnsType<TenantRow> = [
    {
      title: "租户",
      dataIndex: "name",
      render: (name: string, row: TenantRow) => (
        <div className={styles.org}>
          <button
            type="button"
            className={styles.orgName}
            onClick={() => void openDetail(row)}
          >
            {name}
          </button>
          <span className={styles.orgId}>{row.id.slice(0, 8)}</span>
        </div>
      ),
    },
    {
      title: "状态",
      dataIndex: "active",
      width: 120,
      render: (active: boolean) => (
        <span
          className={`${styles.tag} ${active ? styles.tagOk : styles.tagOff}`}
        >
          {active ? "正常" : "停用"}
        </span>
      ),
    },
    {
      title: "开通时间",
      dataIndex: "created_at",
      width: 180,
      render: (value: string) => (
        <span className={styles.time}>{formatTime(value)}</span>
      ),
    },
    {
      title: "操作",
      key: "actions",
      width: 180,
      render: (_: unknown, row: TenantRow) => {
        const canToggle = row.active ? canSuspend : canResume;
        if (!canToggle && !canResetPassword) return null;
        return (
          <div className={styles.headActions}>
            {canToggle ? (
              <Button type="link" size="small" onClick={() => setTarget(row)}>
                {row.active ? "停用" : "恢复"}
              </Button>
            ) : null}
            {canResetPassword ? (
              <Button
                type="link"
                size="small"
                onClick={() => {
                  setIssued(null);
                  setAdmins([]);
                  setResetFor(row);
                  void openReset(row);
                }}
              >
                重置密码
              </Button>
            ) : null}
          </div>
        );
      },
    },
  ];

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1 className={styles.title}>租户管理</h1>
          <p className={styles.sub}>
            只显示租户名称、状态与开通时间，不展示任何业务数据。
          </p>
        </div>
        <div className={styles.headActions}>
          <Button
            type="primary"
            disabled={!canCreate}
            onClick={() => setWizardOpen(true)}
          >
            开通租户
          </Button>
        </div>
      </div>

      <StatStrip
        items={[
          {
            label: "租户总数",
            value: summary?.total ?? total,
            detail: "全平台",
          },
          {
            label: "正常",
            value: summary?.active ?? "—",
            detail: "可登录",
          },
          {
            label: "停用",
            value: summary?.suspended ?? "—",
            detail: "数据保留",
          },
          {
            label: "本月开通",
            value: summary?.thisMonth ?? "—",
            detail: "按开通时间",
          },
        ]}
      />

      <section className={styles.panel} aria-label="租户列表">
        {phase !== "denied" ? (
          <div className={styles.listHead}>
            <Tabs
              activeKey={status || "ALL"}
              onChange={(key) => {
                setStatus(key === "ALL" ? "" : key);
                setPage(0);
              }}
              items={[
                {
                  key: "ALL",
                  label: `全部${summary ? ` ${summary.total}` : ""}`,
                },
                {
                  key: "ACTIVE",
                  label: `正常${summary ? ` ${summary.active}` : ""}`,
                },
                {
                  key: "SUSPENDED",
                  label: `停用${summary ? ` ${summary.suspended}` : ""}`,
                },
              ]}
            />
            <div className={styles.listFilters}>
              <Input.Search
                allowClear
                placeholder="搜索机构名称"
                value={search}
                style={{ width: 220 }}
                onChange={(event) => setSearch(event.target.value)}
                onSearch={(value) => {
                  setKeyword(value.trim());
                  setPage(0);
                }}
              />
              <Button
                onClick={() => {
                  setSearch("");
                  setKeyword("");
                  setStatus("");
                  setPage(0);
                }}
              >
                清空筛选
              </Button>
            </div>
          </div>
        ) : null}
        {phase === "denied" ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>没有查看租户的权限</p>
            <p className={styles.stateHint}>
              当前账号缺少 TENANT_READ，请联系平台管理员开通后重试。
            </p>
          </div>
        ) : phase === "failed" ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>租户列表加载失败</p>
            <p className={styles.stateHint}>{errorText}</p>
            <Button
              type="primary"
              style={{ marginTop: 16 }}
              onClick={() => void load(page)}
            >
              重试
            </Button>
          </div>
        ) : phase === "ready" && rows.length === 0 ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>还没有租户</p>
            <p className={styles.stateHint}>
              开通第一个租户后，客户即可用它自己的管理员账号登录并建校。
            </p>
            {canCreate ? (
              <Button
                type="primary"
                style={{ marginTop: 16 }}
                onClick={() => setWizardOpen(true)}
              >
                开通第一个租户
              </Button>
            ) : null}
          </div>
        ) : phase === "loading" && rows.length === 0 ? (
          <div className={styles.stateBlock}>
            <Skeleton active paragraph={{ rows: 6 }} />
          </div>
        ) : (
          <>
            <Table<TenantRow>
              rowKey="id"
              size="middle"
              columns={columns}
              dataSource={rows}
              pagination={false}
              loading={phase === "loading"}
              locale={{ emptyText: "本页没有租户" }}
            />
            <div className={styles.panelFoot}>
              <span>
                共 {total} 个租户 · 第 {total === 0 ? 0 : page * PAGE_SIZE + 1}–
                {Math.min(total, (page + 1) * PAGE_SIZE)} 条
              </span>
              <div className={styles.pager}>
                <button
                  type="button"
                  className={styles.pageBtn}
                  disabled={page === 0 || phase === "loading"}
                  onClick={() => void load(page - 1)}
                >
                  上一页
                </button>
                <button
                  type="button"
                  className={styles.pageBtn}
                  disabled={
                    phase === "loading" || (page + 1) * PAGE_SIZE >= total
                  }
                  onClick={() => void load(page + 1)}
                >
                  下一页
                </button>
              </div>
            </div>
          </>
        )}
      </section>

      <Modal
        open={target !== null}
        title={target === null ? "" : target.active ? "停用租户" : "恢复租户"}
        onCancel={() => {
          if (!busy) setTarget(null);
        }}
        closable={!busy}
        maskClosable={!busy}
        footer={[
          <Button
            key="cancel"
            autoFocus
            disabled={busy}
            onClick={() => setTarget(null)}
          >
            取消
          </Button>,
          <Button
            key="ok"
            danger={target?.active === true}
            loading={busy}
            onClick={() => void confirmStatusChange()}
          >
            {target?.active ? "停用" : "恢复"}
          </Button>,
        ]}
      >
        <p className={styles.modalText}>
          {target === null
            ? null
            : target.active
              ? `停用后「${target.name}」的所有人将无法登录，数据保留并可随时恢复。`
              : `恢复后「${target.name}」的成员可以重新登录。`}
        </p>
      </Modal>
      <Drawer
        open={detail !== null || detailBusy || !!detailError}
        title={detail?.name ?? "租户详情"}
        width={480}
        onClose={closeDetail}
        destroyOnHidden
      >
        {detailBusy && !detail ? (
          <Skeleton active paragraph={{ rows: 5 }} />
        ) : detailError ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>租户详情加载失败</p>
            <p className={styles.stateHint}>{detailError}</p>
          </div>
        ) : detail ? (
          <Descriptions column={1} size="small">
            <Descriptions.Item label="状态">
              <span
                className={`${styles.tag} ${detail.active ? styles.tagOk : styles.tagOff}`}
              >
                {detail.active ? "正常" : "停用"}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="开通时间">
              {formatTime(detail.created_at)}
            </Descriptions.Item>
            <Descriptions.Item label="学校">{detail.schools}</Descriptions.Item>
            <Descriptions.Item label="账号">
              {detail.accounts}
            </Descriptions.Item>
            <Descriptions.Item label="租户管理员">
              {detail.administrators}
            </Descriptions.Item>
            <Descriptions.Item label="租户 ID">
              <span className={styles.orgId}>{detail.id}</span>
            </Descriptions.Item>
          </Descriptions>
        ) : null}
        {detail && !detailBusy ? (
          <Button
            style={{ marginTop: 16 }}
            onClick={() => void openBranding(detail.id, detail.name)}
          >
            代改品牌
          </Button>
        ) : null}
      </Drawer>

      <ProvisionWizard
        open={wizardOpen}
        canCreate={canCreate}
        onClose={() => setWizardOpen(false)}
        onCreated={() => void load(0)}
        onSessionLost={onSessionLost}
      />

      <Modal
        open={resetFor !== null}
        title={resetFor === null ? "" : `重置「${resetFor.name}」的管理员密码`}
        footer={null}
        closable={!resetBusy}
        maskClosable={!resetBusy}
        onCancel={() => {
          if (!resetBusy) {
            setResetFor(null);
            setIssued(null);
          }
        }}
      >
        {resetFor === null ? null : issued ? (
          <div className={styles.result}>
            <p className={styles.stateTitle}>已生成新的初始密码</p>
            <p className={styles.stateHint}>
              只显示这一次；客户用新密码登录后会被要求再次改密，原有会话同时失效。
            </p>
            <dl className={styles.credential}>
              <dt>登录名</dt>
              <dd>{issued.login}</dd>
              <dt>初始密码</dt>
              <dd>
                <code>{issued.password}</code>
              </dd>
            </dl>
            <Button onClick={() => void copyIssued()}>复制登录信息</Button>
          </div>
        ) : (
          <>
            <p className={styles.stateHint}>
              选择要重置的租户管理员。重置后其既有会话立即失效，且下次登录必须改密。
            </p>
            {adminsLoading ? (
              <Skeleton active paragraph={{ rows: 3 }} />
            ) : admins.length === 0 ? (
              <p className={styles.stateHint}>该租户没有可重置的管理员。</p>
            ) : (
              admins.map((admin) => (
                <div key={admin.login} className={styles.adminRow}>
                  <span className={styles.adminLogin}>
                    {admin.login}
                    {admin.active ? "" : "（已停用）"}
                  </span>
                  <Button
                    size="small"
                    loading={resettingLogin === admin.login}
                    disabled={!admin.active || resettingLogin !== null}
                    onClick={() => void issuePassword(resetFor, admin.login)}
                  >
                    重置
                  </Button>
                </div>
              ))
            )}
          </>
        )}
      </Modal>

      <Modal
        open={brandingFor !== null}
        title={
          brandingFor === null
            ? "代改品牌"
            : `代改「${brandingFor.name}」的品牌`
        }
        width={540}
        onCancel={() => {
          if (!brandingBusy) closeBranding();
        }}
        footer={[
          <Button key="cancel" disabled={brandingBusy} onClick={closeBranding}>
            取消
          </Button>,
          <Button
            key="save"
            type="primary"
            loading={brandingBusy}
            disabled={!branding?.canWrite || !brandingSeed}
            onClick={() => void saveBranding()}
          >
            保存
          </Button>,
        ]}
      >
        {brandingError ? (
          <p className={styles.loginError} role="alert">
            {brandingError}
          </p>
        ) : null}
        {!branding ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : (
          <>
            <p className={styles.stateHint}>
              平台代改会写入平台审计；保存后租户侧立即生效。布局、字体、圆角与状态色仍由平台锁定。
            </p>
            <label className={styles.adminLogin} htmlFor="platform-brand-name">
              系统显示名
            </label>
            <input
              id="platform-brand-name"
              className={styles.brandInput}
              value={brandingName}
              maxLength={60}
              placeholder="留空时使用租户名称"
              onChange={(event) => setBrandingName(event.target.value)}
              disabled={!branding.canWrite || brandingBusy}
            />
            <div
              className={styles.brandGrid}
              role="radiogroup"
              aria-label="品牌色"
            >
              {branding.presets.map((preset) => (
                <button
                  key={preset.seed}
                  type="button"
                  role="radio"
                  aria-checked={brandingSeed === preset.seed}
                  className={`${styles.brandOption} ${brandingSeed === preset.seed ? styles.brandOptionActive : ""}`}
                  onClick={() => setBrandingSeed(preset.seed)}
                  disabled={!branding.canWrite || brandingBusy}
                >
                  <span
                    className={styles.brandSwatch}
                    style={{ background: preset.seed }}
                    aria-hidden="true"
                  />
                  {preset.name}
                </button>
              ))}
            </div>
            {!branding.canWrite ? (
              <p className={styles.stateHint}>
                当前账号没有代改品牌的权限（需要 TENANT_BRANDING_WRITE）。
              </p>
            ) : null}
            <p className={styles.stateHint}>当前版本 {branding.version}</p>

            <div className={styles.history}>
              <p className={styles.historyTitle}>历史版本（最近 10 版）</p>
              {historyLoading ? (
                <Skeleton active paragraph={{ rows: 2 }} />
              ) : history.length === 0 ? (
                <p className={styles.stateHint}>还没有历史版本。</p>
              ) : (
                history.map((item) => (
                  <div key={item.version} className={styles.historyRow}>
                    <span
                      className={styles.brandSwatch}
                      style={{ background: item.brand }}
                      aria-hidden="true"
                    />
                    <span className={styles.historyMeta}>
                      v{item.version} · {item.displayName ?? "未设置显示名"} ·{" "}
                      {formatTime(item.changedAt)}
                    </span>
                    <Button
                      size="small"
                      disabled={
                        !branding.canWrite ||
                        item.version === branding.version ||
                        rollingBack !== null
                      }
                      loading={rollingBack === item.version}
                      onClick={() => void rollbackBranding(item.version)}
                    >
                      回滚
                    </Button>
                  </div>
                ))
              )}
            </div>
          </>
        )}
      </Modal>
    </>
  );
}
