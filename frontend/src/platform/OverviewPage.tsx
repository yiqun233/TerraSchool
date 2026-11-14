import { useCallback, useEffect, useState } from "react";
import { Button, Skeleton, Table } from "antd";
import type { TableColumnsType } from "antd";
import {
  AccessDenied,
  fetchOverview,
  SessionExpired,
  type AuditEventRow,
  type PlatformOverview,
  type ProvisioningRecord,
} from "./api";
import { auditActionLabel, formatTime, provisioningErrorLabel } from "./format";
import { StatStrip, StatusTag } from "../shared/ui/Page";
import styles from "./platform.module.css";

type Phase = "loading" | "ready" | "denied" | "failed";

const provisioningColumns: TableColumnsType<ProvisioningRecord> = [
  {
    title: "时间",
    dataIndex: "created_at",
    width: 148,
    render: (value: string | null) => (
      <span className={styles.time}>{formatTime(value)}</span>
    ),
  },
  {
    title: "机构",
    dataIndex: "tenant_name",
    render: (value: string | null) =>
      value ? <span className={styles.orgName}>{value}</span> : "—",
  },
  {
    title: "管理员",
    dataIndex: "admin_login",
    render: (value: string | null) => value ?? "—",
  },
  {
    title: "操作者",
    dataIndex: "operator_name",
    render: (_: string, row) => row.operator_name || row.operator_login,
  },
  {
    title: "结果",
    dataIndex: "status",
    width: 150,
    render: (status: ProvisioningRecord["status"], row) =>
      status === "ACTIVE" ? (
        <StatusTag tone="ok">成功</StatusTag>
      ) : (
        <StatusTag tone="danger">
          失败 · {provisioningErrorLabel(row.error_code)}
        </StatusTag>
      ),
  },
];

const auditColumns: TableColumnsType<AuditEventRow> = [
  {
    title: "时间",
    dataIndex: "happened_at",
    width: 148,
    render: (value: string) => (
      <span className={styles.time}>{formatTime(value)}</span>
    ),
  },
  {
    title: "动作",
    dataIndex: "action",
    render: (value: string) => auditActionLabel(value),
  },
  {
    title: "操作者",
    dataIndex: "operator_name",
    render: (_: string, row) => row.operator_name || row.operator_login,
  },
  {
    title: "目标租户",
    dataIndex: "tenant_name",
    render: (value: string | null) => value ?? "—",
  },
  {
    title: "变更摘要",
    dataIndex: "changed_fields",
    render: (value: string) => value || "—",
  },
];

/**
 * 总览：租户状态与规模，加上最近的开通与审计。
 *
 * <p>缺少 PROVISIONING_READ / PLATFORM_AUDIT_READ 时服务端返回空摘要，页面显示“暂无记录”，
 * 不把它当成错误——权限不足不等于系统坏了。
 */
export default function OverviewPage({
  onSessionLost,
  onOpen,
}: {
  onSessionLost: () => void;
  onOpen: (target: "provisioning" | "audit") => void;
}) {
  const [phase, setPhase] = useState<Phase>("loading");
  const [data, setData] = useState<PlatformOverview | null>(null);
  const [errorText, setErrorText] = useState("");

  const load = useCallback(async () => {
    setPhase("loading");
    try {
      setData(await fetchOverview());
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
      setErrorText(error instanceof Error ? error.message : "加载总览失败");
      setPhase("failed");
    }
  }, [onSessionLost]);

  useEffect(() => {
    void load();
  }, [load]);

  if (phase === "denied") {
    return (
      <section className={styles.panel}>
        <div className={styles.stateBlock}>
          <p className={styles.stateTitle}>没有查看平台总览的权限</p>
          <p className={styles.stateHint}>
            当前账号缺少 TENANT_READ，请联系平台管理员开通后重试。
          </p>
        </div>
      </section>
    );
  }

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1 className={styles.title}>总览</h1>
          <p className={styles.sub}>
            只汇总租户状态与规模，以及平台自身的最近操作；不展示任何租户业务数据。
          </p>
        </div>
        <div className={styles.headActions}>
          <Button onClick={() => void load()} loading={phase === "loading"}>
            刷新
          </Button>
        </div>
      </div>

      {phase === "failed" ? (
        <section className={styles.panel}>
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>总览加载失败</p>
            <p className={styles.stateHint}>{errorText}</p>
            <Button
              type="primary"
              style={{ marginTop: 16 }}
              onClick={() => void load()}
            >
              重试
            </Button>
          </div>
        </section>
      ) : !data ? (
        <section className={styles.panel}>
          <div className={styles.stateBlock}>
            <Skeleton active paragraph={{ rows: 4 }} />
          </div>
        </section>
      ) : (
        <>
          <StatStrip
            items={[
              {
                label: "租户总数",
                value: data.tenants.total,
                detail: "全平台",
              },
              { label: "正常", value: data.tenants.active, detail: "可登录" },
              {
                label: "停用",
                value: data.tenants.suspended,
                detail: "数据保留",
              },
              {
                label: "本月开通",
                value: data.tenants.thisMonth,
                detail: "按开通时间",
              },
            ]}
          />

          <section className={styles.panel} aria-label="平台规模">
            <div className={styles.scaleGrid}>
              <div className={styles.scaleCell}>
                <span className={styles.scaleLabel}>学校总数</span>
                <span className={styles.scaleValue}>{data.schools}</span>
              </div>
              <div className={styles.scaleCell}>
                <span className={styles.scaleLabel}>账号总数</span>
                <span className={styles.scaleValue}>{data.accounts}</span>
              </div>
            </div>
          </section>

          <section className={styles.panel} aria-label="最近开通">
            <div className={styles.sectionHead}>
              <h2 className={styles.sectionTitle}>最近开通</h2>
              <Button type="link" onClick={() => onOpen("provisioning")}>
                查看全部
              </Button>
            </div>
            <Table<ProvisioningRecord>
              rowKey={(row) => `${row.created_at}-${row.admin_login ?? ""}`}
              size="small"
              columns={provisioningColumns}
              dataSource={data.recentProvisioning}
              pagination={false}
              locale={{ emptyText: "还没有开通记录" }}
            />
          </section>

          <section className={styles.panel} aria-label="最近平台操作">
            <div className={styles.sectionHead}>
              <h2 className={styles.sectionTitle}>最近平台操作</h2>
              <Button type="link" onClick={() => onOpen("audit")}>
                查看全部
              </Button>
            </div>
            <Table<AuditEventRow>
              rowKey="trace_id"
              size="small"
              columns={auditColumns}
              dataSource={data.recentAudit}
              pagination={false}
              locale={{ emptyText: "还没有平台操作记录" }}
            />
          </section>
        </>
      )}
    </>
  );
}
