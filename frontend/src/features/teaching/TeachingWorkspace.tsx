import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type LessonView = components["schemas"]["LessonView"];
type ClassMemberView = components["schemas"]["ClassMemberView"];
type Curriculum = components["schemas"]["Curriculum"];

const LESSON_STATUS: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  CONFIRMED: "已确认",
  CLOSED: "已关闭",
};

/**
 * 教务工作台：班级 → 课次 → 发布 → 考勤确认 → 关闭课次（唯一消费固化点）。
 *
 * 页面对话只提交状态与版本；消费金额由服务端按报名冻结单价计算，页面只展示对账结果。
 */
export function TeachingWorkspace({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [classId, setClassId] = useState<string>();
  const [creatingClass, setCreatingClass] = useState(false);
  const [creatingLesson, setCreatingLesson] = useState(false);
  const [attendanceFor, setAttendanceFor] = useState<LessonView | null>(null);
  const [reconcileFor, setReconcileFor] = useState<string | null>(null);
  const [error, setError] = useState("");

  const capabilities = useQuery({
    queryKey: ["teaching-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取教务权限");
      return data;
    },
  });
  const current = schoolId ?? capabilities.data?.schools?.[0]?.id;
  const school = capabilities.data?.schools?.find(
    (item) => item.id === current,
  );
  const classes = useQuery({
    queryKey: ["teaching-classes", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/classes", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data)
        throw new Error("无法读取班级列表，请核对当前权限");
      return data;
    },
  });
  const curricula = useQuery({
    queryKey: ["teaching-curricula", current],
    enabled: !!current && !!school?.maintainable,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/product/catalog", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data.curricula ?? [];
    },
  });
  const lessons = useQuery({
    queryKey: ["teaching-lessons", classId],
    enabled: !!classId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/lessons", {
        params: { query: { classId: classId! } },
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取课次");
      return data;
    },
  });
  const members = useQuery({
    queryKey: ["teaching-members", classId],
    enabled: !!classId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/members",
        {
          params: { path: { id: classId! } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取教务权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无教务读取权限" type="error" />;
  const schools = capabilities.data.schools ?? [];
  if (schools.length === 0)
    return <Alert title="当前范围内没有可查看的学校" type="warning" />;

  return (
    <>
      <Space className="toolbar" wrap>
        <Select
          aria-label="归属学校"
          style={{ width: 200 }}
          value={current}
          options={schools.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
          onChange={(value) => {
            setSchoolId(value);
            setClassId(undefined);
          }}
        />
        {school?.maintainable ? (
          <Button onClick={() => setCreatingClass(true)}>新建班级</Button>
        ) : null}
        <Button
          onClick={() => void classes.refetch()}
          loading={classes.isFetching}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">
          关闭课次才固化消费；草稿与发布不产生课时消耗
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {classes.error ? (
        <Alert title={classes.error.message} type="error" />
      ) : null}
      <Card title="班级" size="small">
        <Table
          rowKey="id"
          size="small"
          loading={classes.isPending}
          dataSource={classes.data ?? []}
          pagination={false}
          onRow={(row) => ({
            onClick: () => setClassId(row.id ?? undefined),
            style: { cursor: "pointer" },
          })}
          columns={[
            { title: "班级", dataIndex: "name" },
            { title: "课程", dataIndex: "curriculumName" },
            { title: "在班人数", dataIndex: "memberCount" },
            {
              title: "状态",
              dataIndex: "status",
              render: (status: string) =>
                status === "ACTIVE" ? (
                  <Tag color="green">进行中</Tag>
                ) : (
                  <Tag>已结班</Tag>
                ),
            },
          ]}
          locale={{ emptyText: "该学校还没有班级" }}
        />
      </Card>
      {classId ? (
        <Card
          title={`课次：${classes.data?.find((item) => item.id === classId)?.name ?? ""}`}
          size="small"
          style={{ marginTop: 16 }}
        >
          <Space wrap style={{ marginBottom: 12 }}>
            {school?.maintainable ? (
              <Button onClick={() => setCreatingLesson(true)}>新建课次</Button>
            ) : null}
            <Button
              onClick={() => void lessons.refetch()}
              loading={lessons.isFetching}
            >
              刷新课次
            </Button>
            <Typography.Text type="secondary">
              在班 {members.data?.length ?? 0} 人
            </Typography.Text>
          </Space>
          <Table
            rowKey="id"
            size="small"
            loading={lessons.isPending}
            dataSource={lessons.data ?? []}
            pagination={false}
            columns={[
              { title: "日期", dataIndex: "lessonDate" },
              {
                title: "时间",
                render: (_: unknown, row: LessonView) =>
                  `${row.startsAt}–${row.endsAt}`,
              },
              { title: "课题", dataIndex: "topic" },
              { title: "课时", dataIndex: "hours" },
              {
                title: "状态",
                dataIndex: "status",
                render: (status: string) => (
                  <Tag>{LESSON_STATUS[status] ?? status}</Tag>
                ),
              },
              { title: "考勤", dataIndex: "attendanceCount" },
              {
                title: "操作",
                key: "actions",
                render: (_: unknown, row: LessonView) => (
                  <Space>
                    {row.status === "DRAFT" ? (
                      <PublishLessonButton
                        lesson={row}
                        onDone={() => void lessons.refetch()}
                        onError={setError}
                      />
                    ) : null}
                    {row.status === "PUBLISHED" ? (
                      <Button
                        size="small"
                        onClick={() => setAttendanceFor(row)}
                      >
                        考勤确认
                      </Button>
                    ) : null}
                    {row.status === "CONFIRMED" && school?.canCloseLesson ? (
                      <CloseLessonButton
                        lesson={row}
                        onDone={() => void lessons.refetch()}
                        onError={setError}
                      />
                    ) : null}
                  </Space>
                ),
              },
            ]}
            locale={{ emptyText: "该班级还没有课次" }}
          />
          <Typography.Title level={5} style={{ marginTop: 16 }}>
            在班报名与对账
          </Typography.Title>
          <Table
            rowKey="enrollmentId"
            size="small"
            dataSource={members.data ?? []}
            pagination={false}
            columns={[
              { title: "学员", dataIndex: "studentName" },
              { title: "入班日期", dataIndex: "joinedOn" },
              {
                title: "操作",
                key: "actions",
                render: (_: unknown, member: ClassMemberView) => (
                  <Button
                    size="small"
                    onClick={() => setReconcileFor(member.enrollmentId ?? null)}
                  >
                    课时对账
                  </Button>
                ),
              },
            ]}
            locale={{ emptyText: "该班级还没有报名" }}
          />
        </Card>
      ) : null}
      {creatingClass && current ? (
        <ClassCreator
          schoolId={current}
          curricula={curricula.data ?? []}
          onCancel={() => setCreatingClass(false)}
          onCreated={() => {
            setCreatingClass(false);
            void classes.refetch();
          }}
        />
      ) : null}
      {creatingLesson && classId ? (
        <LessonCreator
          classId={classId}
          onCancel={() => setCreatingLesson(false)}
          onCreated={() => {
            setCreatingLesson(false);
            void lessons.refetch();
          }}
        />
      ) : null}
      {attendanceFor && classId ? (
        <AttendanceModal
          lesson={attendanceFor}
          members={members.data ?? []}
          onCancel={() => setAttendanceFor(null)}
          onDone={() => {
            setAttendanceFor(null);
            void lessons.refetch();
          }}
          onError={setError}
        />
      ) : null}
      {reconcileFor ? (
        <ReconciliationDrawer
          id={reconcileFor}
          onClose={() => setReconcileFor(null)}
        />
      ) : null}
    </>
  );
}

export function PublishLessonButton({
  lesson,
  onDone,
  onError,
}: {
  lesson: LessonView;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function publish() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/lessons/{id}/publish",
        {
          params: { path: { id: lesson.id! } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: lesson.version },
        },
      );
      if (response.status === 409) {
        onError("课次状态已变化，请刷新后再发布。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("发布失败，请核对教务维护权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size="small" disabled={busy} onClick={() => void publish()}>
      发布
    </Button>
  );
}

export function CloseLessonButton({
  lesson,
  onDone,
  onError,
}: {
  lesson: LessonView;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function close() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/lessons/{id}/close", {
        params: { path: { id: lesson.id! } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: lesson.version },
      });
      if (response.status === 409) {
        onError("课次状态已变化，请刷新后再关闭。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("关闭失败，请核对课次关闭权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size="small" danger disabled={busy} onClick={() => void close()}>
      关闭课次
    </Button>
  );
}

/** 考勤确认：必须覆盖全部在班报名，提交后课次进入已确认。 */
export function AttendanceModal({
  lesson,
  members,
  onCancel,
  onDone,
  onError,
}: {
  lesson: LessonView;
  members: ClassMemberView[];
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [statuses, setStatuses] = useState<Record<string, string>>(() =>
    Object.fromEntries(members.map((item) => [item.enrollmentId!, "PRESENT"])),
  );

  async function submit() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/lessons/{id}/attendance",
        {
          params: { path: { id: lesson.id! } },
          headers: { [token.headerName!]: token.token! },
          body: {
            expectedVersion: lesson.version,
            records: members.map((item) => ({
              enrollmentId: item.enrollmentId,
              status: statuses[item.enrollmentId!] ?? "PRESENT",
            })),
          },
        },
      );
      if (response.status === 409) {
        onError("课次状态或名单已变化，请刷新后重新登记。");
        return;
      }
      if (!response.ok) {
        onError("考勤确认失败，请核对考勤登记权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`考勤确认：${lesson.lessonDate}`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      <Table
        rowKey="enrollmentId"
        size="small"
        dataSource={members}
        pagination={false}
        columns={[
          { title: "学员", dataIndex: "studentName" },
          {
            title: "出勤",
            key: "status",
            render: (_: unknown, member: ClassMemberView) => (
              <Radio.Group
                aria-label={`出勤-${member.studentName}`}
                value={statuses[member.enrollmentId!]}
                onChange={(event) =>
                  setStatuses((current) => ({
                    ...current,
                    [member.enrollmentId!]: event.target.value,
                  }))
                }
                options={[
                  { value: "PRESENT", label: "出勤" },
                  { value: "ABSENT", label: "缺勤" },
                  { value: "LEAVE", label: "请假" },
                ]}
              />
            ),
          },
        ]}
        locale={{ emptyText: "该班级没有在班报名" }}
      />
      <Button
        type="primary"
        style={{ marginTop: 12 }}
        onClick={() => void submit()}
        loading={busy}
        disabled={busy || members.length === 0}
      >
        确认考勤
      </Button>
    </Modal>
  );
}

export function ClassCreator({
  schoolId,
  curricula,
  onCancel,
  onCreated,
}: {
  schoolId: string;
  curricula: Curriculum[];
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/classes", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          schoolId,
          curriculumId: values.curriculumId,
          name: values.name,
        },
      });
      if (!response.ok) {
        setError(
          response.status === 409
            ? "该学校已有同名班级。"
            : "班级创建失败，请核对课程归属。",
        );
        return;
      }
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新建班级"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical">
        <Form.Item
          label="班级名称"
          name="name"
          rules={[
            { required: true, whitespace: true, message: "请输入班级名称" },
          ]}
        >
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="课程"
          name="curriculumId"
          rules={[{ required: true, message: "请选择课程" }]}
        >
          <Select
            options={curricula.map((item) => ({
              value: item.id,
              label: item.name,
            }))}
            disabled={busy}
            notFoundContent="该学校还没有课程"
          />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          创建班级
        </Button>
      </Form>
    </Modal>
  );
}

export function LessonCreator({
  classId,
  onCancel,
  onCreated,
}: {
  classId: string;
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/lessons", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          classId,
          lessonDate: values.lessonDate,
          startsAt: values.startsAt,
          endsAt: values.endsAt,
          teacherName: values.teacherName || undefined,
          roomName: values.roomName || undefined,
          topic: values.topic || undefined,
          hours: String(values.hours),
        },
      });
      if (!response.ok) {
        setError("课次创建失败，请核对日期、时间与课时。");
        return;
      }
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新建课次"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical" initialValues={{ hours: 1 }}>
        <Form.Item
          label="上课日期"
          name="lessonDate"
          rules={[{ required: true, message: "请输入日期" }]}
        >
          <Input placeholder="YYYY-MM-DD" maxLength={10} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="开始时间"
          name="startsAt"
          rules={[{ required: true, message: "请输入开始时间" }]}
        >
          <Input placeholder="09:00" maxLength={5} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="结束时间"
          name="endsAt"
          rules={[{ required: true, message: "请输入结束时间" }]}
        >
          <Input placeholder="10:00" maxLength={5} disabled={busy} />
        </Form.Item>
        <Form.Item label="教师" name="teacherName">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="教室" name="roomName">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="课题" name="topic">
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="授课课时"
          name="hours"
          rules={[{ required: true, message: "请输入课时" }]}
        >
          <InputNumber
            min={0.01}
            max={99}
            step={0.5}
            precision={2}
            style={{ width: "100%" }}
            disabled={busy}
          />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          保存草稿
        </Button>
      </Form>
    </Modal>
  );
}

/** 单条报名的课时对账：购买、已消耗、剩余。 */
export function ReconciliationDrawer({
  id,
  onClose,
}: {
  id: string;
  onClose: () => void;
}) {
  const reconciliation = useQuery({
    queryKey: ["reconciliation", id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/enrollments/{id}/reconciliation",
        {
          params: { path: { id } },
          signal,
        },
      );
      if (!response.ok || !data) throw new Error("无法读取课时对账");
      return data;
    },
  });

  return (
    <Drawer title="课时对账" open size="large" onClose={onClose}>
      {reconciliation.error ? (
        <Alert title={reconciliation.error.message} type="error" />
      ) : null}
      {reconciliation.data ? (
        <>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="报名状态">
              {reconciliation.data.status}
            </Descriptions.Item>
            <Descriptions.Item label="结算模式">
              {reconciliation.data.settlementMode}
            </Descriptions.Item>
            <Descriptions.Item label="购买课时">
              {reconciliation.data.hours}
            </Descriptions.Item>
            <Descriptions.Item label="已消耗课时">
              {reconciliation.data.consumedHours}
            </Descriptions.Item>
            <Descriptions.Item label="剩余课时">
              {reconciliation.data.remainingHours}
            </Descriptions.Item>
            <Descriptions.Item label="已消耗金额">
              {reconciliation.data.consumedAmount}
            </Descriptions.Item>
          </Descriptions>
          <Table
            rowKey="id"
            size="small"
            style={{ marginTop: 12 }}
            dataSource={reconciliation.data.consumptions ?? []}
            pagination={false}
            columns={[
              { title: "课次日期", dataIndex: "lessonDate" },
              { title: "课时", dataIndex: "hours" },
              { title: "金额", dataIndex: "amount" },
              { title: "单价快照", dataIndex: "priceSnapshot" },
            ]}
            locale={{ emptyText: "还没有消费记录" }}
          />
        </>
      ) : null}
    </Drawer>
  );
}
