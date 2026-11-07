import { useCallback, useEffect, useState } from "react";
import { App, Button, Descriptions, Modal, Table } from "antd";
import ProvisionWizard from "./ProvisionWizard";
import type { TableColumnsType } from "antd";
import {
  AccessDenied,
  fetchAdministrators,
  fetchTenantDetail,
  type TenantDetail,
  fetchTenants,
  resetAdminPassword,
  SessionExpired,
  setTenantActive,
  type TenantRow,
} from "./api";
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
  canCreate,
  onSessionLost,
}: {
  canCreate: boolean;
  onSessionLost: () => void;
}) {
  const { message } = App.useApp();
  const [rows, setRows] = useState<TenantRow[]>([]);
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
  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);

  const load = useCallback(
    async (targetPage: number) => {
      setPhase("loading");
      try {
        const items = await fetchTenants(targetPage, PAGE_SIZE);
        setRows(items);
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
    [onSessionLost],
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
    setDetailBusy(true);
    try {
      setDetail(await fetchTenantDetail(row.id));
    } catch (failure) {
      if (failure instanceof SessionExpired) {
        onSessionLost();
        return;
      }
      message.error(
        failure instanceof Error ? failure.message : "加载详情失败",
      );
    } finally {
      setDetailBusy(false);
    }
  }

  async function openReset(row: TenantRow) {
    setResetBusy(true);
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
      setResetBusy(false);
    }
  }

  async function issuePassword(row: TenantRow, login: string) {
    setResetBusy(true);
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
      title: "",
      key: "actions",
      width: 180,
      render: (_: unknown, row: TenantRow) => (
        <div className={styles.headActions}>
          <Button type="link" size="small" onClick={() => setTarget(row)}>
            {row.active ? "停用" : "恢复"}
          </Button>
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
        </div>
      ),
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

      <section className={styles.panel} aria-label="租户列表">
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
        ) : (
          <>
            <Table<TenantRow>
              rowKey="id"
              size="middle"
              columns={columns}
              dataSource={rows}
              pagination={false}
              loading={phase === "loading"}
            />
            <div className={styles.panelFoot}>
              <span>
                第 {page + 1} 页 · 本页 {rows.length} 条
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
                  disabled={rows.length < PAGE_SIZE || phase === "loading"}
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
        okText={target === null ? "" : target.active ? "停用" : "恢复"}
        cancelText="取消"
        okButtonProps={{
          danger: target?.active === true,
          loading: busy,
        }}
        onOk={() => void confirmStatusChange()}
        onCancel={() => {
          if (!busy) setTarget(null);
        }}
        closable={!busy}
        maskClosable={!busy}
      >
        <p className={styles.modalText}>
          {target === null
            ? null
            : target.active
              ? `停用后「${target.name}」的所有人将无法登录，数据保留并可随时恢复。`
              : `恢复后「${target.name}」的成员可以重新登录。`}
        </p>
      </Modal>
      <Modal
        open={detail !== null || detailBusy}
        title={detail?.name ?? "租户详情"}
        footer={null}
        onCancel={() => setDetail(null)}
      >
        {detail ? (
          <Descriptions column={2} size="small">
            <Descriptions.Item label="状态">
              {detail.active ? "正常" : "停用"}
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
        ) : (
          <p className={styles.stateHint}>正在加载…</p>
        )}
      </Modal>

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
          </div>
        ) : (
          <>
            <p className={styles.stateHint}>
              选择要重置的租户管理员。重置后其既有会话立即失效，且下次登录必须改密。
            </p>
            {admins.length === 0 && !resetBusy ? (
              <p className={styles.stateHint}>该租户没有可重置的管理员。</p>
            ) : null}
            {admins.map((admin) => (
              <div key={admin.login} className={styles.adminRow}>
                <span className={styles.adminLogin}>
                  {admin.login}
                  {admin.active ? "" : "（已停用）"}
                </span>
                <Button
                  size="small"
                  loading={resetBusy}
                  disabled={!admin.active}
                  onClick={() => void issuePassword(resetFor, admin.login)}
                >
                  重置
                </Button>
              </div>
            ))}
          </>
        )}
      </Modal>
    </>
  );
}
