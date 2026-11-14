import { useCallback, useEffect, useState } from "react";
import { Button, Input, Skeleton, Table, Tabs } from "antd";
import type { TableColumnsType } from "antd";
import {
  AccessDenied,
  fetchProvisioningRequests,
  SessionExpired,
  type ProvisioningRecord,
} from "./api";
import { formatTime, provisioningErrorLabel } from "./format";
import { StatusTag } from "../shared/ui/Page";
import styles from "./platform.module.css";

const PAGE_SIZE = 20;

type Phase = "loading" | "ready" | "denied" | "failed";

const columns: TableColumnsType<ProvisioningRecord> = [
  {
    title: "提交时间",
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
    title: "管理员登录名",
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
    width: 210,
    render: (status: ProvisioningRecord["status"], row) =>
      status === "ACTIVE" ? (
        <StatusTag tone="ok">成功</StatusTag>
      ) : (
        <StatusTag tone="danger">
          失败 · {provisioningErrorLabel(row.error_code)}
        </StatusTag>
      ),
  },
  {
    title: "完成时间",
    dataIndex: "finished_at",
    width: 148,
    render: (value: string | null) => (
      <span className={styles.time}>{formatTime(value)}</span>
    ),
  },
];

/**
 * 开通记录：成功与失败都留痕。
 *
 * <p>失败行没有租户，靠提交时的机构名与登录名说明“谁失败了”；错误码是稳定业务码，
 * 页面按固定映射翻成可读原因，未登记的码原样显示。
 */
export default function ProvisioningPage({
  onSessionLost,
}: {
  onSessionLost: () => void;
}) {
  const [phase, setPhase] = useState<Phase>("loading");
  const [rows, setRows] = useState<ProvisioningRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [keyword, setKeyword] = useState("");
  const [errorText, setErrorText] = useState("");

  const load = useCallback(
    async (target: number) => {
      setPhase("loading");
      try {
        const data = await fetchProvisioningRequests(
          target,
          PAGE_SIZE,
          status,
          keyword,
        );
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
          error instanceof Error ? error.message : "加载开通记录失败",
        );
        setPhase("failed");
      }
    },
    [keyword, onSessionLost, status],
  );

  useEffect(() => {
    void load(0);
  }, [load]);

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1 className={styles.title}>开通记录</h1>
          <p className={styles.sub}>
            每一次开通尝试都留一行：成功记录指向租户，失败记录带可读原因与提交参数。
          </p>
        </div>
        <div className={styles.headActions}>
          <Button onClick={() => void load(page)} loading={phase === "loading"}>
            刷新
          </Button>
        </div>
      </div>

      <section className={styles.panel} aria-label="开通记录">
        {phase !== "denied" ? (
          <div className={styles.listHead}>
            <Tabs
              activeKey={status || "ALL"}
              onChange={(key) => {
                setStatus(key === "ALL" ? "" : key);
                setPage(0);
              }}
              items={[
                { key: "ALL", label: "全部" },
                { key: "ACTIVE", label: "成功" },
                { key: "FAILED", label: "失败" },
              ]}
            />
            <div className={styles.listFilters}>
              <Input.Search
                allowClear
                placeholder="搜索机构名或管理员登录名"
                value={search}
                style={{ width: 240 }}
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
            <p className={styles.stateTitle}>没有查看开通记录的权限</p>
            <p className={styles.stateHint}>
              当前账号缺少 PROVISIONING_READ，请联系平台管理员开通后重试。
            </p>
          </div>
        ) : phase === "failed" ? (
          <div className={styles.stateBlock}>
            <p className={styles.stateTitle}>开通记录加载失败</p>
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
            <p className={styles.stateTitle}>没有符合条件的开通记录</p>
            <p className={styles.stateHint}>
              开通租户后这里会留下记录；失败的开通同样会留痕。
            </p>
          </div>
        ) : (
          <>
            <Table<ProvisioningRecord>
              rowKey={(row) => `${row.created_at}-${row.admin_login ?? ""}`}
              size="middle"
              columns={columns}
              dataSource={rows}
              pagination={false}
              loading={phase === "loading"}
              locale={{ emptyText: "本页没有开通记录" }}
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
