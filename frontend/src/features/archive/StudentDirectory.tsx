import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type StudentProfile = components["schemas"]["StudentProfile"];
type StudentSummary = components["schemas"]["StudentSummary"];
type StudentMergeHistory = components["schemas"]["StudentMergeHistory"];
type CreateStudent = components["schemas"]["CreateStudent"];
type DuplicateHint = components["schemas"]["DuplicateHint"];
type EmployeeAssignmentOption =
  components["schemas"]["EmployeeAssignmentOption"];
type StudentExportTask = {
  id: string;
  schoolId: string;
  format: "CSV" | "XLSX";
  status:
    | "PENDING"
    | "RUNNING"
    | "COMPLETED"
    | "FAILED"
    | "CANCELLED"
    | "EXPIRED";
  attemptCount: number;
  rowCount: number | null;
  errorCode: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
};

const SEX_LABEL: Record<string, string> = {
  MALE: "男",
  FEMALE: "女",
  UNKNOWN: "未填写",
};
const RELATION_LABEL: Record<string, string> = {
  FATHER: "父亲",
  MOTHER: "母亲",
  GUARDIAN: "监护人",
  OTHER: "其他",
};

/**
 * 学员与家长档案工作区。
 *
 * 敏感字段由服务端脱敏后返回，页面只提示“当前为脱敏值”，不做二次还原；
 * 重复档案只提示核验；人工合并需显式选择主档，服务端拦截已有业务事实的源档。
 */
export function StudentDirectory({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const exportRequestStarted = useRef(false);
  const [exportTask, setExportTask] = useState<StudentExportTask | null>(null);
  // 导出按学校范围进行：取当前账号第一所可读学校。
  const [search, setSearch] = useState("");
  const [keyword, setKeyword] = useState("");
  const [detailId, setDetailId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [hint, setHint] = useState<DuplicateHint[]>([]);

  const capabilities = useQuery({
    queryKey: ["archive-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/archive/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取学员档案权限");
      return data;
    },
  });
  // 导出按学校范围进行：取当前账号第一所可读学校。
  const exportSchool = capabilities.data?.exportableSchools?.[0]?.id;
  const students = useQuery({
    queryKey: ["archive-students", page, keyword],
    enabled: !!capabilities.data?.canRead,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/archive/students", {
        params: { query: { page, size: 20, search: keyword } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data)
        throw new Error("无法读取学员档案，请核对当前权限");
      return data;
    },
  });

  useEffect(() => {
    if (!exportSchool || !capabilities.data?.canExport) {
      setExportTask(null);
      return;
    }
    const controller = new AbortController();
    void fetch(
      `/api/exports?exportType=STUDENT&schoolId=${encodeURIComponent(exportSchool)}`,
      { credentials: "same-origin", signal: controller.signal },
    )
      .then(async (response) => {
        if (!response.ok) throw new Error("无法恢复最近的学员导出任务");
        const tasks = (await response.json()) as StudentExportTask[];
        if (!exportRequestStarted.current) setExportTask(tasks[0] ?? null);
      })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name !== "AbortError") {
          setError(cause.message);
        }
      });
    return () => controller.abort();
  }, [exportSchool, capabilities.data?.canExport]);

  useEffect(() => {
    if (!exportTask || !["PENDING", "RUNNING"].includes(exportTask.status))
      return;
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      try {
        const response = await fetch(
          `/api/archive/student-exports/${encodeURIComponent(exportTask.id)}`,
          { credentials: "same-origin" },
        );
        if (response.status === 401) {
          onUnauthorized();
          return;
        }
        if (!response.ok) throw new Error("无法读取导出任务状态");
        const next = (await response.json()) as StudentExportTask;
        if (!cancelled) setExportTask(next);
      } catch {
        if (!cancelled) setError("读取导出进度失败，可点击刷新重试。");
      }
    }, 1000);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [exportTask, onUnauthorized]);

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取学员档案权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无学员档案读取权限" type="error" />;

  /** 导出以持久任务执行；下载时服务端复核学校与敏感字段权限。 */
  async function exportStudents(format: "CSV" | "XLSX") {
    if (!exportSchool) return;
    exportRequestStarted.current = true;
    setExporting(true);
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
          exportType: "STUDENT",
          schoolId: exportSchool,
          requestId: crypto.randomUUID(),
          format,
        }),
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok) {
        const detail = await response.json().catch(() => null);
        setError(
          detail?.code === "EXPORT_LIMIT_EXCEEDED"
            ? "学员超过 5000 条，暂不支持完整导出。"
            : "导出失败，请核对学员读取权限。",
        );
        return;
      }
      setExportTask((await response.json()) as StudentExportTask);
    } catch {
      setError("无法创建导出任务，请稍后重试。");
    } finally {
      setExporting(false);
    }
  }

  async function updateExportTask(action: "cancel" | "retry") {
    if (!exportTask) return;
    try {
      const token = await csrf();
      const response = await fetch(
        `/api/exports/${encodeURIComponent(exportTask.id)}/${action}`,
        {
          method: "POST",
          credentials: "same-origin",
          headers: { [token.headerName!]: token.token! },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error("任务操作失败");
      setExportTask((await response.json()) as StudentExportTask);
    } catch {
      setError(action === "cancel" ? "取消导出失败。" : "重试导出失败。");
    }
  }

  async function refreshExportTask() {
    if (!exportTask) return;
    try {
      const response = await fetch(
        `/api/exports/${encodeURIComponent(exportTask.id)}`,
        { credentials: "same-origin" },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error("任务状态不可用");
      setExportTask((await response.json()) as StudentExportTask);
      setError("");
    } catch {
      setError("读取导出进度失败，请稍后重试。");
    }
  }

  return (
    <>
      <Space className="toolbar">
        {capabilities.data.canMaintain ? (
          <Button
            onClick={() => {
              setError("");
              setHint([]);
              setCreating(true);
            }}
          >
            新增学员
          </Button>
        ) : null}
        <Input.Search
          placeholder="按姓名、学员号或有权限的电话检索"
          allowClear
          value={search}
          style={{ width: 260 }}
          onChange={(event) => setSearch(event.target.value)}
          onSearch={(value) => {
            setPage(0);
            setKeyword(value.trim());
          }}
        />
        <Button
          onClick={() => void students.refetch()}
          loading={students.isFetching}
        >
          刷新
        </Button>
        <Button
          onClick={() => void exportStudents("CSV")}
          loading={exporting}
          disabled={!capabilities.data.canExport || !exportSchool || exporting}
        >
          导出 CSV
        </Button>
        <Button
          onClick={() => void exportStudents("XLSX")}
          loading={exporting}
          disabled={!capabilities.data.canExport || !exportSchool || exporting}
        >
          导出 XLSX
        </Button>
        {exportTask ? (
          <Space>
            <Typography.Text role="status">
              {exportTask.format} 导出任务：
              {exportTask.status === "PENDING"
                ? "等待执行"
                : exportTask.status === "RUNNING"
                  ? `处理中（第 ${exportTask.attemptCount} 次）`
                  : exportTask.status === "COMPLETED"
                    ? `完成，共 ${exportTask.rowCount ?? 0} 条`
                    : exportTask.status === "FAILED"
                      ? `失败（${exportTask.errorCode === "EXPORT_LIMIT_EXCEEDED" ? "超过 5000 条限制" : (exportTask.errorCode ?? "未知原因")}）`
                      : exportTask.status === "CANCELLED"
                        ? "已取消"
                        : "文件已过期，请重新导出"}
            </Typography.Text>
            {exportTask.status === "PENDING" ||
            exportTask.status === "RUNNING" ? (
              <Button onClick={() => void refreshExportTask()}>刷新进度</Button>
            ) : null}
            {exportTask.status === "PENDING" ? (
              <Button onClick={() => void updateExportTask("cancel")}>
                取消
              </Button>
            ) : null}
            {exportTask.status === "FAILED" ? (
              <Button onClick={() => void updateExportTask("retry")}>
                重试
              </Button>
            ) : null}
            {exportTask.status === "COMPLETED" ? (
              <Button
                onClick={() =>
                  void (async () => {
                    const response = await fetch(
                      `/api/exports/${encodeURIComponent(exportTask.id)}/download`,
                      { credentials: "same-origin" },
                    );
                    if (!response.ok) {
                      setError("下载失败，可能是权限已变更或文件已过期。");
                      return;
                    }
                    const url = URL.createObjectURL(await response.blob());
                    const anchor = document.createElement("a");
                    anchor.href = url;
                    anchor.download = `students-${exportTask.schoolId}.${exportTask.format.toLowerCase()}`;
                    anchor.click();
                    URL.revokeObjectURL(url);
                  })()
                }
              >
                下载
              </Button>
            ) : null}
          </Space>
        ) : null}
        <Typography.Text type="secondary">
          敏感字段按当前权限脱敏显示；导出同样按字段权限脱敏
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {hint.length ? (
        <Alert
          type="warning"
          showIcon
          title="发现可能重复的档案，请人工核验后再决定是否继续登记"
          description={
            <ul>
              {hint.map((item) => (
                <li key={item.studentId}>
                  {item.studentNo} {item.name}（
                  {item.reason === "SAME_NAME_AND_BIRTHDAY"
                    ? "姓名与出生日期相同"
                    : "姓名与电话相同"}
                  ）
                </li>
              ))}
            </ul>
          }
        />
      ) : null}
      <Table
        rowKey="id"
        loading={students.isPending}
        dataSource={students.data?.items ?? []}
        onRow={(row) => ({
          onClick: () => setDetailId(row.id ?? null),
          style: { cursor: "pointer" },
        })}
        columns={[
          { title: "学员号", dataIndex: "studentNo" },
          { title: "姓名", dataIndex: "name" },
          {
            title: "性别",
            dataIndex: "sex",
            render: (sex: string) => SEX_LABEL[sex] ?? sex,
          },
          { title: "电话", dataIndex: "phone" },
          { title: "归属学校", dataIndex: "schoolName" },
          {
            title: "负责人",
            dataIndex: "ownerAccountId",
            render: (owner: string | null) => (owner ? "已分派" : "未分派"),
          },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) =>
              status === "ACTIVE" ? (
                <Tag color="green">在读</Tag>
              ) : (
                <Tag>停用</Tag>
              ),
          },
          { title: "联系人", dataIndex: "contactCount" },
        ]}
        locale={{ emptyText: "当前范围内暂无学员档案" }}
        pagination={{
          current: page + 1,
          pageSize: 20,
          total: students.data?.total ?? 0,
          showSizeChanger: false,
          onChange: (next) => setPage(next - 1),
        }}
      />
      {creating ? (
        <StudentCreator
          schools={capabilities.data.maintainableSchools ?? []}
          onCancel={() => setCreating(false)}
          onCreated={(profile, duplicates) => {
            setCreating(false);
            setHint(duplicates);
            setPage(0);
            setKeyword("");
            setSearch("");
            void students.refetch();
            if (profile?.id) setDetailId(profile.id);
          }}
        />
      ) : null}
      {detailId ? (
        <StudentDetail
          id={detailId}
          canMaintain={!!capabilities.data.canMaintain}
          canAssign={!!capabilities.data.canAssign}
          onMerged={setDetailId}
          onClose={() => setDetailId(null)}
          onSaved={() => void students.refetch()}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
    </>
  );
}

/**
 * 新建学员；结果不确定时保留同一个 requestId 重试，换参数会被服务端拒绝。
 */
export function StudentCreator({
  schools,
  onCancel,
  onCreated,
}: {
  schools: components["schemas"]["StudentSchoolOption"][];
  onCancel: () => void;
  onCreated: (
    profile: StudentProfile | undefined,
    duplicates: DuplicateHint[],
  ) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();
  const pending = useRef<CreateStudent | null>(null);

  async function save() {
    const values = await form.validateFields();
    const body: CreateStudent = {
      requestId: pending.current?.requestId ?? crypto.randomUUID(),
      schoolId: values.schoolId,
      name: values.name,
      nameEnglish: values.nameEnglish || undefined,
      sex: values.sex,
      birthday: values.birthday || undefined,
      certType: values.certCode ? values.certType : undefined,
      certCode: values.certCode || undefined,
      phone: values.phone || undefined,
      email: values.email || undefined,
      contacts: values.guardianName
        ? [
            {
              name: values.guardianName,
              relation: values.guardianRelation ?? "OTHER",
              phone: values.guardianPhone || undefined,
              primary: true,
            },
          ]
        : [],
      confirmDuplicate: false,
    };
    if (!pending.current) pending.current = body;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST("/api/archive/students", {
        headers: { [token.headerName!]: token.token! },
        body: pending.current,
      });
      if (!response.ok) {
        if (response.status >= 500 || response.status === 408) {
          setError("结果尚未确认，请使用相同内容重试。");
        } else if (response.status === 409) {
          pending.current = null;
          setError(
            "该证件号已存在档案，或同一请求号提交了不同内容；请核验后重新填写。",
          );
        } else {
          pending.current = null;
          setError("学员档案创建失败，请核对必填项与权限。");
        }
        return;
      }
      pending.current = null;
      onCreated(data?.profile, data?.possibleDuplicates ?? []);
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新增学员档案"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      keyboard={!busy}
      footer={null}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Form
        form={form}
        layout="vertical"
        initialValues={{ sex: "UNKNOWN", certType: "ID_CARD" }}
      >
        <Form.Item
          label="归属学校"
          name="schoolId"
          rules={[{ required: true, message: "请选择学校" }]}
        >
          <Select
            options={schools.map((item) => ({
              value: item.id,
              label: item.name,
            }))}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item
          label="学员姓名"
          name="name"
          rules={[
            { required: true, whitespace: true, message: "请输入学员姓名" },
          ]}
        >
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="英文名" name="nameEnglish">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="性别" name="sex">
          <Select
            options={[
              { value: "MALE", label: "男" },
              { value: "FEMALE", label: "女" },
              { value: "UNKNOWN", label: "未填写" },
            ]}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item label="出生日期" name="birthday">
          <Input placeholder="YYYY-MM-DD" maxLength={10} disabled={busy} />
        </Form.Item>
        <Form.Item label="证件类型" name="certType">
          <Select
            options={[
              { value: "ID_CARD", label: "身份证" },
              { value: "PASSPORT", label: "护照" },
              { value: "OTHER", label: "其他" },
            ]}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item label="证件号" name="certCode">
          <Input maxLength={40} disabled={busy} />
        </Form.Item>
        <Form.Item label="学员电话" name="phone">
          <Input maxLength={30} disabled={busy} />
        </Form.Item>
        <Form.Item label="邮箱" name="email">
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Typography.Text type="secondary">家长/联系人（可选）</Typography.Text>
        <Form.Item label="联系人姓名" name="guardianName">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="与学员关系" name="guardianRelation">
          <Select
            options={Object.entries(RELATION_LABEL).map(([value, label]) => ({
              value,
              label,
            }))}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item label="联系人电话" name="guardianPhone">
          <Input maxLength={30} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          创建档案
        </Button>
      </Form>
    </Modal>
  );
}

/** 详情抽屉：展示脱敏状态，并只允许改姓名与状态（带版本，冲突需重新读取）。 */
export function StudentDetail({
  id,
  canMaintain,
  canAssign,
  onMerged,
  onClose,
  onSaved,
  onUnauthorized,
}: {
  id: string;
  canMaintain: boolean;
  canAssign: boolean;
  onMerged: (studentId: string) => void;
  onClose: () => void;
  onSaved: () => void;
  onUnauthorized: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [selectedOwner, setSelectedOwner] = useState<string>();
  const [mergeOpen, setMergeOpen] = useState(false);
  const pendingReverse = useRef(
    new Map<string, { fingerprint: string; requestId: string }>(),
  );
  const pendingAssignment = useRef<{
    fingerprint: string;
    requestId: string;
  } | null>(null);
  const detail = useQuery({
    queryKey: ["archive-student", id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/archive/students/{id}", {
        params: { path: { id } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (response.status === 403)
        throw new Error("当前账号无权查看该学员档案");
      if (!response.ok || !data) throw new Error("无法读取学员档案");
      return data;
    },
  });
  const assignees = useQuery({
    queryKey: ["archive-student-assignees", detail.data?.schoolId],
    enabled: canAssign && !!detail.data?.schoolId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/archive/students/assignees",
        {
          params: { query: { schoolId: detail.data!.schoolId! } },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取同校有效学员负责人");
      return data;
    },
  });
  const mergeHistory = useQuery({
    queryKey: ["archive-student-merges", id],
    enabled: !!detail.data?.id,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/archive/students/{id}/merges",
        { params: { path: { id } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取学员合并记录");
      return data;
    },
  });

  async function setStatus(status: string) {
    if (!detail.data) return;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/archive/students/{id}/status", {
        params: { path: { id } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: detail.data.version, status },
      });
      if (response.status === 409) {
        setError("档案已被他人修改，请关闭后重新读取再提交。");
        await detail.refetch();
        return;
      }
      if (!response.ok) {
        setError("更新失败，请核对权限或稍后重试。");
        return;
      }
      await detail.refetch();
      onSaved();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  async function assignOwner() {
    if (!detail.data || !selectedOwner) return;
    const reason = window.prompt("请输入学员归属调整原因")?.trim();
    if (!reason) return;
    const fingerprint = `${detail.data.version}|${selectedOwner}|${reason}`;
    const requestId =
      pendingAssignment.current?.fingerprint === fingerprint
        ? pendingAssignment.current.requestId
        : crypto.randomUUID();
    pendingAssignment.current = { fingerprint, requestId };
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/archive/students/{id}/owner", {
        params: { path: { id } },
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId,
          expectedVersion: detail.data.version,
          ownerAccountId: selectedOwner,
          reason,
        },
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (response.status === 409) {
        setError("学员档案或请求参数已变化，请重新读取后核对负责人。");
        pendingAssignment.current = null;
        await detail.refetch();
        return;
      }
      if (!response.ok) {
        setError("分派失败：请核对学员分派权限和同校有效负责人。");
        return;
      }
      pendingAssignment.current = null;
      setSelectedOwner(undefined);
      await detail.refetch();
      onSaved();
    } catch {
      setError("分派结果暂时未知，请以相同参数重试后核对负责人。");
    } finally {
      setBusy(false);
    }
  }

  async function reverseMerge(item: StudentMergeHistory) {
    if (!item.sourceStudentId || item.sourceVersion == null) return;
    const reason = window.prompt("请输入撤销合并原因")?.trim();
    if (!reason) return;
    const fingerprint = `${item.sourceVersion}|${reason}`;
    const previous = pendingReverse.current.get(item.sourceStudentId);
    const requestId =
      previous?.fingerprint === fingerprint
        ? previous.requestId
        : crypto.randomUUID();
    pendingReverse.current.set(item.sourceStudentId, {
      fingerprint,
      requestId,
    });
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/archive/students/{id}/merge/reverse",
        {
          params: { path: { id: item.sourceStudentId } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId,
            expectedSourceVersion: item.sourceVersion,
            reason,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setError(
          "撤销失败：请核对源档版本、权限及是否已有新业务关联。原请求可重试。",
        );
        return;
      }
      pendingReverse.current.delete(item.sourceStudentId);
      await Promise.all([detail.refetch(), mergeHistory.refetch()]);
      onSaved();
    } catch {
      setError("撤销结果暂时未知，请用相同原因重试或刷新合并记录核对。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer
      title={detail.data ? `学员 ${detail.data.studentNo}` : "学员档案"}
      open
      size="large"
      onClose={onClose}
    >
      {detail.isPending ? <p role="status">正在读取档案…</p> : null}
      {detail.error ? (
        <Alert title={detail.error.message} type="error" />
      ) : null}
      {error ? <Alert title={error} type="error" /> : null}
      {detail.data ? (
        <>
          {detail.data.sensitiveFieldsMasked ? (
            <Alert
              type="info"
              showIcon
              title="当前显示脱敏值"
              description="字段级权限决定每个敏感字段是否可见；下方标「无字段权限」的项需要单独授权。"
            />
          ) : null}
          {detail.data.mergedIntoStudentId ? (
            <Alert
              type="warning"
              showIcon
              title={`该档案已合并至 ${detail.data.mergedIntoStudentId}，当前只保留源档历史，不能再维护。`}
            />
          ) : null}
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="当前负责人">
              {detail.data.ownerAccountId
                ? (assignees.data?.find(
                    (item) => item.accountId === detail.data.ownerAccountId,
                  )?.name ?? "已分派")
                : "未分派"}
            </Descriptions.Item>
            <Descriptions.Item label="姓名">
              {detail.data.name}
            </Descriptions.Item>
            <Descriptions.Item label="英文名">
              {detail.data.nameEnglish ?? "—"}
            </Descriptions.Item>
            <Descriptions.Item label="性别">
              {SEX_LABEL[detail.data.sex ?? ""] ?? "—"}
            </Descriptions.Item>
            <Descriptions.Item label="出生日期">
              {detail.data.birthday ?? "—"}
              {detail.data.maskedFields?.includes("STUDENT_BIRTHDAY") ? (
                <Tag>无字段权限</Tag>
              ) : null}
            </Descriptions.Item>
            <Descriptions.Item label="证件号">
              {detail.data.certCode ?? "—"}
              {detail.data.maskedFields?.includes("STUDENT_CERT") ? (
                <Tag>无字段权限</Tag>
              ) : null}
            </Descriptions.Item>
            <Descriptions.Item label="电话">
              {detail.data.phone ?? "—"}
              {detail.data.maskedFields?.includes("STUDENT_PHONE") ? (
                <Tag>无字段权限</Tag>
              ) : null}
            </Descriptions.Item>
            <Descriptions.Item label="邮箱">
              {detail.data.email ?? "—"}
              {detail.data.maskedFields?.includes("STUDENT_EMAIL") ? (
                <Tag>无字段权限</Tag>
              ) : null}
            </Descriptions.Item>
            <Descriptions.Item label="归属学校">
              {detail.data.schoolName ?? "—"}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {detail.data.status === "ACTIVE" ? "在读" : "停用"}
            </Descriptions.Item>
          </Descriptions>
          {canAssign && !detail.data.mergedIntoStudentId ? (
            <Space style={{ marginTop: 16 }}>
              <Select
                aria-label="学员负责人"
                placeholder="选择同校有效负责人"
                value={selectedOwner ?? undefined}
                onChange={setSelectedOwner}
                loading={assignees.isPending}
                options={(assignees.data ?? []).map(
                  (item: EmployeeAssignmentOption) => ({
                    value: item.accountId,
                    label: item.name,
                  }),
                )}
                style={{ width: 240 }}
              />
              <Button
                onClick={() => void assignOwner()}
                loading={busy}
                disabled={busy || !selectedOwner}
              >
                分派负责人
              </Button>
            </Space>
          ) : null}
          {canMaintain && !detail.data.mergedIntoStudentId ? (
            <Space style={{ marginTop: 16 }}>
              <Button disabled={busy} onClick={() => setMergeOpen(true)}>
                合并重复档案
              </Button>
              <Typography.Text type="secondary">
                仅同校同负责人且无报名、资金或客户来源事实的源档可合并；不搬迁联系人和历史。
              </Typography.Text>
            </Space>
          ) : null}
          {(mergeHistory.data?.length ?? 0) > 0 ? (
            <>
              <Typography.Title level={5} style={{ marginTop: 16 }}>
                合并记录
              </Typography.Title>
              <Table
                rowKey="mergeId"
                size="small"
                pagination={false}
                dataSource={mergeHistory.data ?? []}
                columns={[
                  { title: "源学员号", dataIndex: "sourceStudentNo" },
                  { title: "源档姓名", dataIndex: "sourceName" },
                  { title: "合并时间", dataIndex: "mergedAt" },
                  {
                    title: "状态",
                    dataIndex: "reversedAt",
                    render: (value: string | null) =>
                      value ? (
                        <Tag>已撤销</Tag>
                      ) : (
                        <Tag color="blue">合并中</Tag>
                      ),
                  },
                  {
                    title: "操作",
                    render: (_: unknown, item: StudentMergeHistory) =>
                      item.reversedAt ? null : (
                        <Button
                          size="small"
                          disabled={busy || !canMaintain}
                          onClick={() => void reverseMerge(item)}
                        >
                          撤销合并
                        </Button>
                      ),
                  },
                ]}
              />
            </>
          ) : null}
          <Typography.Title level={5}>家长与联系人</Typography.Title>
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={detail.data.contacts ?? []}
            columns={[
              { title: "姓名", dataIndex: "name" },
              {
                title: "关系",
                dataIndex: "relation",
                render: (relation: string) =>
                  RELATION_LABEL[relation] ?? relation,
              },
              { title: "电话", dataIndex: "phone" },
              {
                title: "主要联系人",
                dataIndex: "primary",
                render: (primary: boolean) => (primary ? "是" : "—"),
              },
            ]}
            locale={{ emptyText: "尚未登记联系人" }}
          />
          {canMaintain && !detail.data.mergedIntoStudentId ? (
            <Space style={{ marginTop: 16 }}>
              <Button
                disabled={busy}
                onClick={() =>
                  void setStatus(
                    detail.data?.status === "ACTIVE" ? "INACTIVE" : "ACTIVE",
                  )
                }
              >
                {detail.data.status === "ACTIVE" ? "停用档案" : "恢复档案"}
              </Button>
              <Typography.Text type="secondary">
                修改会校验版本，冲突时需重新读取
              </Typography.Text>
            </Space>
          ) : null}
        </>
      ) : null}
      {mergeOpen && detail.data ? (
        <StudentMergeModal
          source={detail.data}
          onCancel={() => setMergeOpen(false)}
          onDone={(primaryStudentId) => {
            setMergeOpen(false);
            onMerged(primaryStudentId);
            onSaved();
          }}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
    </Drawer>
  );
}

function StudentMergeModal({
  source,
  onCancel,
  onDone,
  onUnauthorized,
}: {
  source: StudentProfile;
  onCancel: () => void;
  onDone: (primaryStudentId: string) => void;
  onUnauthorized: () => void;
}) {
  const [keyword, setKeyword] = useState("");
  const [primaryId, setPrimaryId] = useState<string>();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const pending = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );
  const candidates = useQuery({
    queryKey: [
      "archive-student-merge-candidates",
      source.schoolId,
      keyword.trim(),
    ],
    enabled: keyword.trim().length >= 2,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/archive/students", {
        params: { query: { search: keyword.trim(), page: 0, size: 20 } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法检索可选主档");
      return (data.items ?? []).filter(
        (item) =>
          item.id !== source.id &&
          item.schoolId === source.schoolId &&
          (item.ownerAccountId ?? null) === (source.ownerAccountId ?? null) &&
          item.status === "ACTIVE",
      );
    },
  });

  async function merge() {
    if (!primaryId || !reason.trim() || busy) return;
    const primary = candidates.data?.find((item) => item.id === primaryId);
    if (!primary || primary.version == null || source.version == null) {
      setError("请重新检索主档并确认版本信息。");
      return;
    }
    const fingerprint = `${primaryId}|${source.version}|${primary.version}|${reason.trim()}`;
    const requestId =
      pending.current?.fingerprint === fingerprint
        ? pending.current.requestId
        : crypto.randomUUID();
    pending.current = { fingerprint, requestId };
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/archive/students/{id}/merge", {
        params: { path: { id: source.id! } },
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId,
          primaryStudentId: primaryId,
          expectedSourceVersion: source.version,
          expectedPrimaryVersion: primary.version,
          reason: reason.trim(),
        },
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setError(
          response.status === 409
            ? "合并未完成：档案版本已变化，或源档已有报名、收款、资金流水或客户来源记录。"
            : "合并失败，请核对同校同负责人权限及业务状态。",
        );
        return;
      }
      pending.current = null;
      onDone(primaryId);
    } catch {
      setError("合并结果暂时未知，请保持主档与原因不变后使用相同请求重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`合并重复档案 · ${source.studentNo ?? ""} ${source.name ?? ""}`}
      open
      onCancel={onCancel}
      onOk={() => void merge()}
      okText="确认合并"
      cancelText="取消"
      confirmLoading={busy}
      okButtonProps={{ disabled: !primaryId || !reason.trim() || busy }}
      mask={{ closable: !busy }}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {candidates.error ? (
        <Alert title={candidates.error.message} type="error" showIcon />
      ) : null}
      <Typography.Paragraph type="secondary">
        合并只建立可撤销的档案关系，不搬迁资料；有报名、资金或客户来源事实的源档暂不允许合并。
      </Typography.Paragraph>
      <Form layout="vertical">
        <Form.Item label="主档（姓名、学员号或电话检索）" required>
          <Select
            aria-label="合并主档"
            showSearch
            filterOption={false}
            searchValue={keyword}
            onSearch={setKeyword}
            value={primaryId}
            onChange={setPrimaryId}
            loading={candidates.isFetching}
            placeholder="输入至少 2 个字符，检索同校同负责人的有效档案"
            options={(candidates.data ?? []).map((item: StudentSummary) => ({
              value: item.id!,
              label: `${item.studentNo ?? ""} ${item.name ?? "未命名学员"}`,
            }))}
          />
        </Form.Item>
        <Form.Item label="合并原因" required>
          <Input.TextArea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={200}
            rows={3}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
