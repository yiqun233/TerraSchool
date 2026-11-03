import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Input, Modal, Space, Typography } from "antd";
import { csrf } from "../../shared/http";

type Followup = {
  id: string;
  content: string;
  occurredOn: string;
  nextContactOn: string | null;
};
type Pending = { requestId: string; fingerprint: string };
function readPending(key: string): Pending | null {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? "null");
    if (
      value &&
      typeof value === "object" &&
      "requestId" in value &&
      "fingerprint" in value &&
      typeof value.requestId === "string" &&
      typeof value.fingerprint === "string"
    )
      return { requestId: value.requestId, fingerprint: value.fingerprint };
  } catch {
    /* 写入时仍会检查存储是否可用 */
  }
  return null;
}
async function digest(requestId: string, values: string[]) {
  const hash = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(JSON.stringify([requestId, ...values])),
  );
  return Array.from(new Uint8Array(hash), (v) =>
    v.toString(16).padStart(2, "0"),
  ).join("");
}
export function CustomerFollowups({
  customerId,
  accountKey,
  canEdit,
  onClose,
  onUnauthorized,
}: {
  customerId: string;
  accountKey: string;
  canEdit: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
}) {
  const key = `teraschool.customer-followup.${accountKey}.${customerId}`;
  const [pending, setPending] = useState(() => readPending(key));
  const [content, setContent] = useState("");
  const [occurredOn, setOccurredOn] = useState("");
  const [nextContactOn, setNextContactOn] = useState("");
  const [page, setPage] = useState(0);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const guard = useRef(false);
  const client = useQueryClient();
  const endpoint = `/api/admissions/customers/${encodeURIComponent(customerId)}/followups`;
  const rows = useQuery({
    queryKey: ["customer-followups", accountKey, customerId, page],
    queryFn: async ({ signal }) => {
      const response = await fetch(`${endpoint}?page=${page}&size=20`, {
        credentials: "same-origin",
        cache: "no-store",
        signal,
      });
      if (response.status === 401) onUnauthorized();
      if (!response.ok) throw new Error("无法读取跟进记录，请核对权限后重试。");
      return (await response.json()) as { items: Followup[]; hasMore: boolean };
    },
  });
  async function submit() {
    if (guard.current || !canEdit || rows.error) return;
    const clean = content.trim();
    if (!clean || !occurredOn) {
      setNotice("请填写跟进内容和发生日期。");
      return;
    }
    guard.current = true;
    setBusy(true);
    setNotice("");
    let sent = false;
    try {
      const requestId = pending?.requestId ?? crypto.randomUUID();
      const fingerprint = await digest(requestId, [
        customerId,
        clean,
        occurredOn,
        nextContactOn,
      ]);
      if (pending && fingerprint !== pending.fingerprint) {
        setNotice("输入与待确认请求不一致，请重新填写原内容及日期。");
        return;
      }
      const token = await csrf();
      const next = { requestId, fingerprint };
      localStorage.setItem(key, JSON.stringify(next));
      setPending(next);
      sent = true;
      const response = await fetch(endpoint, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: {
          "Content-Type": "application/json",
          [token.headerName!]: token.token!,
        },
        body: JSON.stringify({
          requestId,
          content: clean,
          occurredOn,
          nextContactOn: nextContactOn || null,
        }),
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        if (!pending && response.status < 500) {
          localStorage.removeItem(key);
          setPending(null);
          setNotice("提交被拒绝，请核对输入与权限。");
        } else setNotice("结果尚未确认，请保留原内容并稍后重试。");
        return;
      }
      localStorage.removeItem(key);
      setPending(null);
      setContent("");
      setOccurredOn("");
      setNextContactOn("");
      setPage(0);
      setNotice("跟进记录已保存。");
      await client.invalidateQueries({
        queryKey: ["customer-followups", accountKey, customerId],
      });
    } catch {
      setNotice(
        sent
          ? "结果尚未确认，请保留原内容并稍后重试。"
          : "无法准备安全请求，请检查浏览器和网络。",
      );
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  return (
    <Modal
      title="客户跟进记录"
      open
      onCancel={onClose}
      footer={<Button onClick={onClose}>关闭</Button>}
    >
      <Typography.Paragraph>
        仅创建人可查看。下次联系日期仅作记录，不会自动提醒；请勿在多个标签页同时提交相同记录。
      </Typography.Paragraph>
      {rows.error ? (
        <Alert type="error" title={rows.error.message} showIcon />
      ) : null}
      {pending ? (
        <Alert
          type="warning"
          title="有待确认请求；请重新填写原内容和日期后重试。"
          showIcon
        />
      ) : null}
      {notice ? <Alert type="info" title={notice} showIcon /> : null}
      {canEdit ? (
        <Space orientation="vertical" style={{ width: "100%", marginTop: 12 }}>
          <Input.TextArea
            aria-label="跟进内容"
            placeholder="跟进内容"
            maxLength={1000}
            value={content}
            onChange={(event) => setContent(event.target.value)}
          />
          <label>
            发生日期{" "}
            <Input
              aria-label="跟进发生日期"
              type="date"
              value={occurredOn}
              onChange={(event) => setOccurredOn(event.target.value)}
            />
          </label>
          <label>
            下次联系日期（可选）{" "}
            <Input
              aria-label="下次联系日期"
              type="date"
              value={nextContactOn}
              onChange={(event) => setNextContactOn(event.target.value)}
            />
          </label>
          <Button
            type="primary"
            loading={busy}
            disabled={!!rows.error}
            onClick={() => void submit()}
          >
            {pending ? "重试保存" : "保存跟进"}
          </Button>
        </Space>
      ) : null}
      {!rows.error &&
        !rows.isFetching &&
        rows.data?.items.map((row) => (
          <div
            key={row.id}
            style={{
              borderTop: "1px solid #ddd",
              marginTop: 12,
              paddingTop: 12,
            }}
          >
            <Typography.Text strong>{row.occurredOn}</Typography.Text>
            <Typography.Paragraph style={{ whiteSpace: "pre-wrap" }}>
              {row.content}
            </Typography.Paragraph>
            {row.nextContactOn ? (
              <Typography.Text>下次联系：{row.nextContactOn}</Typography.Text>
            ) : null}
          </div>
        ))}
      <Space style={{ marginTop: 12 }}>
        <Button disabled={page === 0} onClick={() => setPage(page - 1)}>
          上一页
        </Button>
        <span>第 {page + 1} 页</span>
        <Button
          disabled={!!rows.error || !rows.data?.hasMore}
          onClick={() => setPage(page + 1)}
        >
          下一页
        </Button>
      </Space>
    </Modal>
  );
}
