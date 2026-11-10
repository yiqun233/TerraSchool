import { useEffect, useMemo, useState } from "react";
import { Alert, Button, Space, Typography } from "antd";
import { csrf } from "../../shared/http";
import { newRequestId } from "../../shared/ids";

type ExportType = "ENROLLMENT" | "RECEIPTS" | "LESSONS";
type ExportFormat = "CSV" | "XLSX" | "PDF";
type ExportTask = {
  id: string;
  schoolId: string;
  exportType: ExportType;
  format: ExportFormat;
  status:
    | "PENDING"
    | "RUNNING"
    | "COMPLETED"
    | "FAILED"
    | "CANCELLED"
    | "EXPIRED";
  attemptCount: number;
  rowCount?: number;
  errorCode?: string;
};

const LABEL: Record<ExportType, string> = {
  ENROLLMENT: "报名",
  RECEIPTS: "收款",
  LESSONS: "课次",
};

/** 可轮询、取消和重试的服务器端 CSV/XLSX/PDF 导出任务。 */
export function PersistentCsvExportButton({
  exportType,
  schoolId,
  from,
  to,
}: {
  exportType: ExportType;
  schoolId?: string;
  from?: string;
  to?: string;
}) {
  const [task, setTask] = useState<ExportTask>();
  const [error, setError] = useState("");
  const [request, setRequest] = useState<{ key: string; id: string }>();
  const key = useMemo(
    () => [exportType, schoolId ?? "", from ?? "", to ?? ""].join("\n"),
    [exportType, from, schoolId, to],
  );

  useEffect(() => {
    if (!schoolId) {
      setTask(undefined);
      return;
    }
    const controller = new AbortController();
    void fetch(
      `/api/exports?exportType=${encodeURIComponent(exportType)}&schoolId=${encodeURIComponent(schoolId)}`,
      { credentials: "same-origin", signal: controller.signal },
    )
      .then(async (response) => {
        if (!response.ok) throw new Error("无法读取最近的导出任务");
        const tasks = (await response.json()) as ExportTask[];
        setTask(tasks[0]);
      })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name !== "AbortError") {
          setError(cause.message);
        }
      });
    return () => controller.abort();
  }, [exportType, schoolId]);

  useEffect(() => {
    if (!task || !["PENDING", "RUNNING"].includes(task.status)) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void fetch(`/api/exports/${encodeURIComponent(task.id)}`, {
        credentials: "same-origin",
        signal: controller.signal,
      })
        .then(async (response) => {
          if (!response.ok) throw new Error("无法读取导出任务状态");
          setTask((await response.json()) as ExportTask);
        })
        .catch((cause: unknown) => {
          if (cause instanceof Error && cause.name !== "AbortError") {
            setError(cause.message);
          }
        });
    }, 1200);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [task]);

  async function start(format: ExportFormat) {
    if (!schoolId) return;
    setError("");
    const requestKey = `${key}\n${format}`;
    const requestId = request?.key === requestKey ? request.id : newRequestId();
    setRequest({ key: requestKey, id: requestId });
    try {
      const token = await csrf();
      const response = await fetch("/api/exports", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          [token.headerName!]: token.token!,
        },
        body: JSON.stringify({
          exportType,
          schoolId,
          requestId,
          from,
          to,
          format,
        }),
      });
      if (!response.ok) throw new Error("导出任务创建失败，请检查权限后重试");
      setTask((await response.json()) as ExportTask);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "导出任务创建失败");
    }
  }

  async function taskAction(action: "cancel" | "retry") {
    if (!task) return;
    setError("");
    try {
      const token = await csrf();
      const response = await fetch(
        `/api/exports/${encodeURIComponent(task.id)}/${action}`,
        {
          method: "POST",
          credentials: "same-origin",
          headers: { [token.headerName!]: token.token! },
        },
      );
      if (!response.ok) throw new Error("导出任务操作失败");
      setTask((await response.json()) as ExportTask);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "导出任务操作失败");
    }
  }

  async function download() {
    if (!task) return;
    setError("");
    try {
      const response = await fetch(
        `/api/exports/${encodeURIComponent(task.id)}/download`,
        { credentials: "same-origin" },
      );
      if (!response.ok)
        throw new Error("下载失败，可能是权限已变更或文件已过期");
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${exportType.toLowerCase()}-${task.id}.${task.format.toLowerCase()}`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "下载失败");
    }
  }

  return (
    <Space wrap>
      <Button onClick={() => void start("CSV")} disabled={!schoolId}>
        创建{LABEL[exportType]} CSV 导出任务
      </Button>
      <Button onClick={() => void start("XLSX")} disabled={!schoolId}>
        创建{LABEL[exportType]} XLSX 导出任务
      </Button>
      <Button onClick={() => void start("PDF")} disabled={!schoolId}>
        创建{LABEL[exportType]} PDF 导出任务
      </Button>
      {task ? (
        <>
          <Typography.Text>
            {LABEL[task.exportType]} {task.format} 导出：{task.status}
            {task.rowCount == null ? "" : ` · ${task.rowCount} 行`}
          </Typography.Text>
          {task.status === "COMPLETED" ? (
            <Button onClick={() => void download()}>下载 {task.format}</Button>
          ) : null}
          {task.status === "PENDING" ? (
            <Button onClick={() => void taskAction("cancel")}>取消任务</Button>
          ) : null}
          {task.status === "FAILED" ? (
            <Button onClick={() => void taskAction("retry")}>重试任务</Button>
          ) : null}
        </>
      ) : null}
      {error ? <Alert title={error} type="error" showIcon /> : null}
    </Space>
  );
}
