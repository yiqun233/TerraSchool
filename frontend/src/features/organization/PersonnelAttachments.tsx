import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Modal, Space, Table } from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
type Resource = components["schemas"]["ResourceView"];
type Attachment = components["schemas"]["AttachmentView"];

export function PersonnelAttachments({ row }: { row: Resource }) {
  const [open, setOpen] = useState(false),
    [file, setFile] = useState<File>(),
    [busy, setBusy] = useState(false),
    [locked, setLocked] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const sending = useRef(false),
    pending = useRef<{ id: string; file: File } | null>(null),
    client = useQueryClient(),
    employee = row.id!;
  const list = useQuery({
    queryKey: ["personnel-attachments", employee],
    enabled: open,
    retry: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const result = await api.GET(
        "/api/organization/employees/{employee}/attachments",
        { signal, params: { path: { employee } } },
      );
      if (!result.response.ok || !result.data)
        throw new Error("需要人事档案和员工的查看权限。");
      return result.data;
    },
  });
  function close() {
    if (!busy && !locked) {
      setOpen(false);
      setFile(undefined);
      setError("");
      setBlocked(false);
      pending.current = null;
      client.removeQueries({
        queryKey: ["personnel-attachments", employee],
        exact: true,
      });
    }
  }
  async function upload() {
    if (sending.current || blocked || (!file && !pending.current)) return;
    if (!pending.current)
      pending.current = { id: crypto.randomUUID(), file: file! };
    sending.current = true;
    setBusy(true);
    setError("");
    let sent = false;
    try {
      const token = await csrf();
      const form = new FormData();
      form.append("file", pending.current.file);
      sent = true;
      const response = await fetch(
        `/api/organization/employees/${encodeURIComponent(employee)}/attachments?requestId=${encodeURIComponent(pending.current.id)}`,
        {
          method: "POST",
          credentials: "same-origin",
          headers: { [token.headerName!]: token.token! },
          body: form,
        },
      );
      if (!response.ok) {
        if (response.status >= 500 || response.status === 408) {
          setLocked(true);
          setError("上传结果尚未确认，请重试相同文件。");
        } else if (locked) {
          setLocked(false);
          setBlocked(true);
          setError("先前上传结果仍未确认，请关闭后核对附件列表。");
        } else {
          pending.current = null;
          const problem = (await response.json().catch(() => ({}))) as {
            code?: string;
          };
          setError(
            problem.code === "STORAGE_NOT_CONFIGURED"
              ? "尚未配置私有对象存储，请联系管理员。"
              : "上传被拒绝，请核对权限、文件和大小。",
          );
        }
        return;
      }
      pending.current = null;
      setLocked(false);
      setFile(undefined);
      await list.refetch();
    } catch {
      if (sent) {
        setLocked(true);
        setError("上传结果尚未确认，请重试相同文件。");
      } else {
        if (!locked) pending.current = null;
        setError("无法获取安全令牌，请重试。");
      }
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }
  async function download(item: Attachment) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const response = await fetch(
        `/api/organization/employees/${encodeURIComponent(employee)}/attachments/${encodeURIComponent(item.id!)}`,
        { credentials: "same-origin", cache: "no-store" },
      );
      if (!response.ok) throw new Error();
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = item.name!;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      setError("无法下载，请核对当前权限及文件状态。");
    } finally {
      setBusy(false);
    }
  }
  async function remove(item: Attachment) {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/organization/employees/{employee}/attachments/{id}/remove",
        {
          params: { path: { employee, id: item.id! } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: item.version },
        },
      );
      if (!response.ok) throw new Error();
      await list.refetch();
    } catch {
      setError("移除结果未确认，请关闭后重新核对附件列表。");
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true);
          setError("");
          void list.refetch();
        }}
      >
        档案附件
      </Button>
      <Modal
        title={`档案附件：${row.name}`}
        open={open}
        onCancel={close}
        closable={!busy && !locked}
        mask={{ closable: !busy && !locked }}
        keyboard={!busy && !locked}
        footer={null}
        destroyOnHidden
      >
        <p>附件仅限已授权人员查看，单个文件最大 5 MB；移除后保留历史记录。</p>
        {error ? (
          <Alert type="error" title={error} />
        ) : list.error ? (
          <Alert type="error" title={list.error.message} />
        ) : null}
        <Space>
          <input
            aria-label="选择附件"
            type="file"
            disabled={busy || locked || blocked}
            onChange={(event) => setFile(event.target.files?.[0])}
          />
          <Button
            type="primary"
            onClick={() => void upload()}
            loading={busy}
            disabled={blocked || (!file && !pending.current)}
          >
            {locked ? "重试相同上传" : "上传"}
          </Button>
        </Space>
        <Table<Attachment>
          rowKey="id"
          loading={list.isFetching}
          dataSource={list.error ? [] : (list.data ?? [])}
          pagination={false}
          columns={[
            { title: "名称", dataIndex: "name" },
            {
              title: "大小",
              dataIndex: "size",
              render: (size: number) => `${size} 字节`,
            },
            {
              title: "操作",
              render: (_: unknown, item: Attachment) => (
                <Space>
                  <Button
                    disabled={busy || locked}
                    onClick={() => void download(item)}
                  >
                    下载
                  </Button>
                  <Button
                    disabled={busy || locked}
                    onClick={() => void remove(item)}
                  >
                    移除
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Modal>
    </>
  );
}
