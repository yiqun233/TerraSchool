import { useRef, useState } from "react";
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
type CreateStudent = components["schemas"]["CreateStudent"];
type DuplicateHint = components["schemas"]["DuplicateHint"];

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
 * 重复档案只提示核验，页面不提供自动合并入口。
 */
export function StudentDirectory({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
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
  const exportSchool = capabilities.data?.maintainableSchools?.[0]?.id;
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

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取学员档案权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无学员档案读取权限" type="error" />;

  /** 导出走浏览器下载，服务端已按字段权限脱敏并写审计。 */
  async function exportCsv() {
    if (!exportSchool) return;
    setExporting(true);
    try {
      const response = await fetch(
        `/api/archive/students/export?schoolId=${encodeURIComponent(exportSchool)}`,
        { credentials: "include" },
      );
      if (!response.ok) {
        const detail = await response.json().catch(() => null);
        setError(
          detail?.code === "EXPORT_LIMIT_EXCEEDED"
            ? "学员超过 5000 条，暂不支持完整导出。"
            : "导出失败，请核对学员读取权限。",
        );
        return;
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `students-${exportSchool}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      setError("导出失败，请稍后重试。");
    } finally {
      setExporting(false);
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
          placeholder="按姓名或学员号检索"
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
          onClick={() => void exportCsv()}
          loading={exporting}
          disabled={!exportSchool || exporting}
        >
          导出 CSV
        </Button>
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
  onClose,
  onSaved,
  onUnauthorized,
}: {
  id: string;
  canMaintain: boolean;
  onClose: () => void;
  onSaved: () => void;
  onUnauthorized: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
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
          <Descriptions column={1} size="small" bordered>
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
          {canMaintain ? (
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
    </Drawer>
  );
}
