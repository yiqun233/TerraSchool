import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Input, Modal, Space } from "antd";
import { csrf } from "../../shared/http";

type Address = { id: string; address: string; version: number };
export function ChannelAddress({
  id,
  schoolId,
  canEdit,
  onClose,
  onUnauthorized,
}: {
  id: string;
  schoolId: string;
  canEdit: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
}) {
  const client = useQueryClient();
  const endpoint = `/api/admissions/channels/${encodeURIComponent(id)}/address`;
  const [draft, setDraft] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [locked, setLocked] = useState(false);
  const [denied, setDenied] = useState(false);
  const guard = useRef(false);
  const details = useQuery({
    queryKey: ["channel-address", id],
    queryFn: async ({ signal }) => {
      const response = await fetch(endpoint, {
        credentials: "same-origin",
        cache: "no-store",
        signal,
      });
      if (response.status === 401) onUnauthorized();
      if (!response.ok) throw new Error("无法读取渠道地址，请核对学校权限。");
      return (await response.json()) as Address;
    },
  });
  async function save() {
    if (
      guard.current ||
      locked ||
      !canEdit ||
      !details.data ||
      details.error ||
      details.isFetching
    )
      return;
    guard.current = true;
    setBusy(true);
    setNotice("");
    let sent = false;
    try {
      const token = await csrf();
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
          address: (draft ?? details.data.address).trim(),
          expectedVersion: details.data.version,
        }),
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        if (response.status === 403) setDenied(true);
        setLocked(true);
        setNotice(
          response.status === 409
            ? "渠道已变更，请关闭并重新打开后编辑。"
            : "保存未完成，请关闭并重新打开核对。",
        );
        return;
      }
      setLocked(true);
      setNotice("地址已保存，请关闭后重新打开查看。");
      await client.invalidateQueries({ queryKey: ["channels", schoolId] });
    } catch {
      setNotice(
        sent
          ? "保存结果未确认，请关闭并重新打开核对。"
          : "无法取得安全令牌，请重试。",
      );
      if (sent) setLocked(true);
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  return (
    <Modal
      title="渠道地址"
      open
      onCancel={() => !busy && onClose()}
      footer={
        <Space>
          <Button disabled={busy} onClick={onClose}>
            关闭
          </Button>
          {canEdit ? (
            <Button
              type="primary"
              loading={busy}
              disabled={
                locked || !!details.error || details.isFetching || !details.data
              }
              onClick={() => void save()}
            >
              保存地址
            </Button>
          ) : null}
        </Space>
      }
    >
      {details.error ? (
        <Alert type="error" title={details.error.message} showIcon />
      ) : null}
      {denied ? (
        <Alert
          type="warning"
          title="没有维护渠道地址的权限"
          description="需要渠道维护权限才能修改地址；可联系机构管理员开通，或先关闭本窗口。"
          showIcon
        />
      ) : null}
      {notice ? <Alert type="info" title={notice} showIcon /> : null}
      {!denied && !details.error && !details.isFetching && details.data ? (
        <Input
          aria-label="渠道地址"
          maxLength={50}
          disabled={!canEdit || locked || busy}
          value={draft ?? details.data.address}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="地址（可选）"
        />
      ) : null}
    </Modal>
  );
}
