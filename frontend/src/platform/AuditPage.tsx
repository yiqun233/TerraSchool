import { useCallback, useEffect, useState } from "react";
import { Button, Input, Select, Skeleton, Table } from "antd";
import type { TableColumnsType } from "antd";
import {
  AccessDenied,
  fetchAuditEvents,
  fetchTenants,
  SessionExpired,
  type AuditEventRow,
  type TenantRow,
} from "./api";
import { AUDIT_ACTIONS, auditActionLabel, formatTime } from "./format";
import styles from "./platform.module.css";

const PAGE_SIZE = 20;

type Phase = "loading" | "ready" | "denied" | "failed";

interface Filters {
  action: string;
  tenantId: string;
  operator: string;
  range: string;
}

const EMPTY_FILTERS: Filters = {
  action: "",
  tenantId: "",
  operator: "",
  range: "",
};

/** 快捷时间范围：只做常见运营口径，精确区间由接口接受 ISO 时刻，页面暂不开放自定义。 */
function rangeToInstants(range: string): { from?: string; to?: string } {
  const now = new Date();
  if (range === "7d")
    return { from: new Date(now.getTime() - 7 * 86400000).toISOString() };
  if (range === "30d")
    return { from: new Date(now.getTime() - 30 * 86400000).toISOString() };
  if (range === "month")
    return {
      from: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(),
    };
  return {};
}

const columns: TableColumnsType<AuditEventRow> = [
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
    width: 160,
    render: (value: string) => auditActionLabel(value),
  },
  {
    title: "操作者",
    dataIndex: "operator_name",
    render: (_: string, row) => (
      <span title={row.operator_login}>
        {row.operator_name || row.operator_login}
      </span>
    ),
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
  {
    title: "追踪号",
    dataIndex: "trace_id",
    width: 96,
    render: (value: string) => (
      <span className={styles.orgId} title={value}>
        {value.slice(0, 8)}
      </span>
    ),
  },
];

/**
 * 审计日志：平台自身操作轨迹（开通、停用、重置密码、品牌、登录）。
 *
 * <p>只读，且只读平台审计表；租户审计仍归租户管理员查自己的。按动作、目标租户、
 * 操作者和时间筛选，分页在服务端完成。
 */
export default function AuditPage({
  onSessionLost,
}: {
  onSessionLost: () => void;
}) {
  const [phase, setPhase] = useState<Phase>("loading");
  const [rows, setRows] = useState<AuditEventRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [operatorInput, setOperatorInput] = useState("");
  const [tenants, setTenants] = useState<TenantRow[]>([]);
  const [errorText, setErrorText] = useState("");

  // 目标租户筛选项来自租户列表；没有 TENANT_READ 时只隐藏这个筛选项，不影响审计本身。
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const data = await fetchTenants(0, 100);
        if (!cancelled) setTenants(data.items);
      } catch {
        if (!cancelled) setTenants([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [onSessionLost]);

  const load = useCallback(
    async (target: number) => {
      setPhase("loading");
      try {
        const data = await fetchAuditEvents(target, PAGE_SIZE, {
          action: filters.action,
          tenantId: filters.tenantId,
          operator: filters.operator,
          ...rangeToInstants(filters.range),
        });
        setRows(data.items);
        setTotal(data.total);
        setPage(target);
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
        setErrorText(
          error instanceof Error ? error.message : "加载审计日志失败",
        );
        setPhase("failed");
      }
    },
    [filters, onSessionLost],
  );

  useEffect(() => {
    void load(0);
  }, [load]);

  const filtered =
    filters.action !== "" ||
    filters.tenantId !== "" ||
    filters.operator !== "" ||
    filters.range !== "";

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1 className={styles.title}>审计日志</h1>
          <p className={styles.sub}>
            记录平台对租户做过的动作与操作者，用于事后核对；不包含任何租户业务数据。
          </p>
        </div>
        <div className={styles.headActions}>
          <Button onClick={() => void load(page)} loading={phase === "loading"}>
            刷新
          </Button>
        </div>
      </div>

      <section className={styles.panel} aria-label="审计日志">
        {phase !== "denied" ? (
          <div className={styles.listHead}>
            <div className={styles.listFilters} style={{ paddingTop: 10 }}>
              <Select
                value={filters.action}
                style={{ width: 168 }}
                onChange={(value) =>
                  setFilters((f) => ({ ...f, action: value }))
                }
                options={[
                  { value: "", label: "全部动作" },
                  ...AUDIT_ACTIONS.map((item) => ({
                    value: item.value,
                    label: item.label,
                  })),
                ]}
              />
              <Select
                value={filters.tenantId}
                style={{ width: 200 }}
                showSearch
                optionFilterProp="label"
                onChange={(value) =>
                  setFilters((f) => ({ ...f, tenantId: value }))
                }
                options={[
                  { value: "", label: "全部租户" },
                  ...tenants.map((tenant) => ({
                    value: tenant.id,
                    label: tenant.name,
                  })),
                ]}
              />
              <Input.Search
                allowClear
                placeholder="操作者登录名"
                value={operatorInput}
                style={{ width: 180 }}
                onChange={(event) => setOperatorInput(event.target.value)}
                onSearch={(value) =>
                  setFilters((f) => ({ ...f, operator: value.trim() }))
                }
              />
              <Select
                value={filters.range}
                style={{ width: 132 }}
                onChange={(value) =>
                  setFilters((f) => ({ ...f, range: value }))
                }
                options={[
                  { value: "", label: "全部时间" },
                  { value: "7d", label: "近 7 天" },
                  { value: "30d", label: "近 30 天" },
                  { value: "month", label: "本月" },
                ]}
              />
              <Button
                disabled={!filtered}
                onClick={() => {
                  setOperatorInput("");
                  setFilters(EMPTY_FILTERS);
                }}
              >
                清空筛选
              </Button>
            </div>
          </div>
        ) : null}

        {phase === "denied" ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>没有查看审计日志的权限</p>
            <p className={styles.stateHint}>
              当前账号缺少 PLATFORM_AUDIT_READ，请联系平台管理员开通后重试。
            </p>
          </div>
        ) : phase === "failed" ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>审计日志加载失败</p>
            <p className={styles.stateHint}>{errorText}</p>
            <Button
              type="primary"
              style={{ marginTop: 16 }}
              onClick={() => void load(page)}
            >
              重试
            </Button>
          </div>
        ) : phase === "loading" && rows.length === 0 ? (
          <div className={styles.stateBlock}>
            <Skeleton active paragraph={{ rows: 6 }} />
          </div>
        ) : phase === "ready" && rows.length === 0 ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>没有符合条件的审计记录</p>
            <p className={styles.stateHint}>
              平台做过的开通、停用、重置密码和品牌操作都会记录在这里。
            </p>
          </div>
        ) : (
          <>
            <Table<AuditEventRow>
              rowKey={(row) => `${row.trace_id}-${row.happened_at}`}
              size="middle"
              columns={columns}
              dataSource={rows}
              pagination={false}
              loading={phase === "loading"}
              locale={{ emptyText: "本页没有审计记录" }}
            />
            <div className={styles.panelFoot}>
              <span>
                共 {total} 条 · 第 {total === 0 ? 0 : page * PAGE_SIZE + 1}–
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
    </>
  );
}
