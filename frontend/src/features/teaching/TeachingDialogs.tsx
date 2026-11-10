import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import type { LessonView, ClassMemberView, Curriculum } from "./teachingTypes";
import { newRequestId } from "../../shared/ids";

/**
 * 教务工作台的弹窗与面板：发布/关闭课次、调课、换课、补课、考勤、名额、建班、对账。
 *
 * 单独成文件是因为它们共享同一个“草稿 → 提交 → 版本冲突重载”的交互模式，
 * 与工作台的列表/筛选状态无关；放在一起会让 2600 行的页面文件继续膨胀。
 */

function lessonDurationMinutes(lesson: LessonView) {
  const minutes = (value: string | null | undefined) => {
    const [hours, remainder] = (value ?? "").slice(0, 5).split(":").map(Number);
    return hours * 60 + remainder;
  };
  return minutes(lesson.endsAt) - minutes(lesson.startsAt);
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

export function RescheduleLessonModal({
  lesson,
  onCancel,
  onDone,
  onError,
}: {
  lesson: LessonView;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [lessonDate, setLessonDate] = useState(lesson.lessonDate ?? "");
  const [startsAt, setStartsAt] = useState((lesson.startsAt ?? "").slice(0, 5));
  const [endsAt, setEndsAt] = useState((lesson.endsAt ?? "").slice(0, 5));
  const [reason, setReason] = useState("");
  const requestRef = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );

  async function submit() {
    const fingerprint = JSON.stringify([
      lesson.version,
      lessonDate,
      startsAt,
      endsAt,
      reason.trim(),
    ]);
    if (!requestRef.current || requestRef.current.fingerprint !== fingerprint)
      requestRef.current = { fingerprint, requestId: newRequestId() };
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/lessons/{id}/reschedule",
        {
          params: { path: { id: lesson.id! } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId: requestRef.current.requestId,
            expectedVersion: lesson.version,
            lessonDate,
            startsAt,
            endsAt,
            reason: reason.trim(),
          },
        },
      );
      if (response.status === 409) {
        onError("课次或资源状态已变化，请刷新后核对调课结果。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("调课失败，请核对权限、时间和冲突提示。");
        return;
      }
      onDone();
    } catch {
      onError("调课结果未确认；保持本次内容重试可安全防重。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="调整已发布课次"
      open
      okText="保存调课"
      cancelText="取消"
      confirmLoading={busy}
      onOk={() => void submit()}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">
        仅支持尚未登记考勤或消费的课次；原时间与新时间会留存审计记录。
      </Typography.Paragraph>
      <Space orientation="vertical" style={{ width: "100%" }}>
        <label>
          上课日期
          <Input
            aria-label="调课日期"
            type="date"
            value={lessonDate}
            onChange={(event) => setLessonDate(event.target.value)}
          />
        </label>
        <label>
          开始时间
          <Input
            aria-label="调课开始时间"
            type="time"
            value={startsAt}
            onChange={(event) => setStartsAt(event.target.value)}
          />
        </label>
        <label>
          结束时间
          <Input
            aria-label="调课结束时间"
            type="time"
            value={endsAt}
            onChange={(event) => setEndsAt(event.target.value)}
          />
        </label>
        <label>
          调课原因
          <Input.TextArea
            aria-label="调课原因"
            maxLength={200}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
      </Space>
    </Modal>
  );
}

export function SwapLessonModal({
  lesson,
  candidates,
  onCancel,
  onDone,
  onError,
}: {
  lesson: LessonView;
  candidates: LessonView[];
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [otherId, setOtherId] = useState<string>();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const requestRef = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );

  async function submit() {
    const other = candidates.find((item) => item.id === otherId);
    if (!other) {
      onError("请选择同班的另一节已发布课次。");
      return;
    }
    const fingerprint = JSON.stringify([
      lesson.id,
      lesson.version,
      other.id,
      other.version,
      reason.trim(),
    ]);
    if (!requestRef.current || requestRef.current.fingerprint !== fingerprint)
      requestRef.current = { fingerprint, requestId: newRequestId() };
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/lessons/{id}/swap", {
        params: { path: { id: lesson.id! } },
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: requestRef.current.requestId,
          otherLessonId: other.id!,
          expectedVersion: lesson.version,
          expectedOtherVersion: other.version,
          reason: reason.trim(),
        },
      });
      if (response.status === 409) {
        onError("课次或资源状态已变化，请刷新后核对换课结果。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("换课失败，请核对同班范围、考勤状态和时间冲突。");
        return;
      }
      onDone();
    } catch {
      onError("换课结果未确认；保持本次内容重试可安全防重。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="交换课次时段"
      open
      okText="确认换课"
      cancelText="取消"
      confirmLoading={busy}
      onOk={() => void submit()}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">
        只允许同班、尚未登记考勤或消费的已发布课次；交换日期与时间，保留各自教师、教室和课题。
      </Typography.Paragraph>
      <Space orientation="vertical" style={{ width: "100%" }}>
        <label>
          交换对象
          <Select
            aria-label="换课对象"
            style={{ width: "100%" }}
            value={otherId}
            onChange={setOtherId}
            options={candidates.map((item) => ({
              value: item.id!,
              label: `${item.lessonDate} ${item.startsAt}–${item.endsAt} ${item.topic ?? ""}`,
            }))}
          />
        </label>
        <label>
          换课原因
          <Input.TextArea
            aria-label="换课原因"
            maxLength={200}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
      </Space>
    </Modal>
  );
}

export function MakeupLessonModal({
  lesson,
  onCancel,
  onDone,
  onError,
}: {
  lesson: LessonView;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const nextDate = new Date(`${lesson.lessonDate}T00:00:00`);
  nextDate.setDate(nextDate.getDate() + 1);
  const [lessonDate, setLessonDate] = useState(
    `${nextDate.getFullYear()}-${String(nextDate.getMonth() + 1).padStart(2, "0")}-${String(nextDate.getDate()).padStart(2, "0")}`,
  );
  const [startsAt, setStartsAt] = useState((lesson.startsAt ?? "").slice(0, 5));
  const [endsAt, setEndsAt] = useState((lesson.endsAt ?? "").slice(0, 5));
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const requestRef = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );

  async function submit() {
    const fingerprint = JSON.stringify([
      lesson.id,
      lessonDate,
      startsAt,
      endsAt,
      reason.trim(),
    ]);
    if (!requestRef.current || requestRef.current.fingerprint !== fingerprint)
      requestRef.current = { fingerprint, requestId: newRequestId() };
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/lessons/{id}/makeups",
        {
          params: { path: { id: lesson.id! } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId: requestRef.current.requestId,
            lessonDate,
            startsAt,
            endsAt,
            reason: reason.trim(),
          },
        },
      );
      if (response.status === 409) {
        onError("原课次、班级或资源状态已变化，请刷新后核对补课结果。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("补课安排失败，请核对原课次缺勤、班级状态和资源冲突。");
        return;
      }
      onDone();
    } catch {
      onError("补课结果未确认；保持本次内容重试可安全防重。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="为缺勤课次安排补课"
      open
      okText="创建补课草稿"
      cancelText="取消"
      confirmLoading={busy}
      onOk={() => void submit()}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">
        仅已关闭且登记了缺勤/请假的课次可安排。新课次沿用班级、教师、教室和课时；后续正常发布、登记全班考勤并按实际出勤固化消费。
      </Typography.Paragraph>
      <Space orientation="vertical" style={{ width: "100%" }}>
        <label>
          补课日期
          <Input
            aria-label="补课日期"
            type="date"
            value={lessonDate}
            onChange={(event) => setLessonDate(event.target.value)}
          />
        </label>
        <label>
          开始时间
          <Input
            aria-label="补课开始时间"
            type="time"
            value={startsAt}
            onChange={(event) => setStartsAt(event.target.value)}
          />
        </label>
        <label>
          结束时间
          <Input
            aria-label="补课结束时间"
            type="time"
            value={endsAt}
            onChange={(event) => setEndsAt(event.target.value)}
          />
        </label>
        <label>
          补课原因
          <Input.TextArea
            aria-label="补课原因"
            maxLength={200}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
      </Space>
    </Modal>
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
    Object.fromEntries(
      members.map((item) => [
        item.enrollmentId!,
        lesson.makeupSourceLessonId &&
        !(lesson.makeupEnrollmentIds ?? []).includes(item.enrollmentId!)
          ? "ABSENT"
          : "PRESENT",
      ]),
    ),
  );
  const [attendedMinutes, setAttendedMinutes] = useState<
    Record<string, number>
  >({});

  async function submit() {
    const duration = lessonDurationMinutes(lesson);
    if (
      members.some((member) => {
        const id = member.enrollmentId!;
        return (
          statuses[id] === "PARTIAL" &&
          (!attendedMinutes[id] ||
            attendedMinutes[id] <= 0 ||
            attendedMinutes[id] >= duration)
        );
      })
    ) {
      onError("部分缺勤请填写大于 0 且少于课次时长的实际出勤分钟。");
      return;
    }
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
              ...(statuses[item.enrollmentId!] === "PARTIAL"
                ? { attendedMinutes: attendedMinutes[item.enrollmentId!] }
                : {}),
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
      {lesson.makeupSourceLessonId ? (
        <Typography.Paragraph type="secondary">
          补课默认只勾选原课次缺勤或请假学员，其他学员保持未出勤，避免重复扣课时。
        </Typography.Paragraph>
      ) : null}
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
            render: (_: unknown, member: ClassMemberView) => {
              const id = member.enrollmentId!;
              return (
                <Space orientation="vertical" size={4}>
                  <Radio.Group
                    aria-label={`出勤-${member.studentName}`}
                    value={statuses[id]}
                    onChange={(event) =>
                      setStatuses((current) => ({
                        ...current,
                        [id]: event.target.value,
                      }))
                    }
                    options={[
                      { value: "PRESENT", label: "出勤" },
                      { value: "PARTIAL", label: "部分缺勤" },
                      { value: "ABSENT", label: "缺勤" },
                      { value: "LEAVE", label: "请假" },
                    ]}
                  />
                  {statuses[id] === "PARTIAL" ? (
                    <Space>
                      <InputNumber
                        aria-label={`实际出勤分钟-${member.studentName}`}
                        min={1}
                        max={Math.max(1, lessonDurationMinutes(lesson) - 1)}
                        precision={0}
                        value={attendedMinutes[id]}
                        onChange={(value) =>
                          setAttendedMinutes((current) => ({
                            ...current,
                            [id]: typeof value === "number" ? value : 0,
                          }))
                        }
                      />
                      <Typography.Text>分钟</Typography.Text>
                    </Space>
                  ) : null}
                </Space>
              );
            },
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

export function ClassCapacityEditor({
  classView,
  onCancel,
  onUpdated,
  onUnauthorized,
}: {
  classView: components["schemas"]["ClassView"];
  onCancel: () => void;
  onUpdated: () => void;
  onUnauthorized: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();
  const pendingRequest = useRef<{
    fingerprint: string;
    requestId: string;
  } | null>(null);

  async function save() {
    const values = await form.validateFields();
    const capacity = values.capacity ?? null;
    const reason = String(values.reason ?? "").trim();
    const fingerprint = `${classView.id}|${classView.version}|${capacity ?? "UNLIMITED"}|${reason}`;
    if (pendingRequest.current?.fingerprint !== fingerprint) {
      pendingRequest.current = { fingerprint, requestId: newRequestId() };
    }
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/classes/{id}/capacity",
        {
          params: { path: { id: classView.id! } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId: pendingRequest.current.requestId,
            expectedVersion: classView.version ?? 0,
            capacity,
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
          response.status === 409
            ? "班级状态或版本已变化，或名额低于当前在班人数；刷新后核对。"
            : "调整名额失败，请核对教务维护权限和填写内容。",
        );
        return;
      }
      onUpdated();
    } catch {
      setError("调整结果暂未确认；保持相同内容重试可安全防重。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`调整班级名额 · ${classView.name ?? ""}`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Form
        form={form}
        layout="vertical"
        initialValues={{ capacity: classView.capacity ?? undefined }}
      >
        <Form.Item
          label="名额上限"
          name="capacity"
          extra={`当前在班 ${classView.memberCount ?? 0} 人；留空表示不限。新上限不能低于当前在班人数。`}
          rules={[
            {
              validator: async (_, value?: number) => {
                if (
                  value == null ||
                  (Number.isInteger(value) &&
                    value >= (classView.memberCount ?? 0) &&
                    value <= 100000)
                )
                  return;
                throw new Error(
                  `名额须为 ${classView.memberCount ?? 0}–100000 的整数`,
                );
              },
            },
          ]}
        >
          <InputNumber
            min={classView.memberCount ?? 0}
            max={100000}
            precision={0}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item
          label="调整原因"
          name="reason"
          rules={[
            { required: true, whitespace: true, message: "请填写调整原因" },
          ]}
        >
          <Input maxLength={200} disabled={busy} />
        </Form.Item>
      </Form>
      <Space>
        <Button onClick={onCancel} disabled={busy}>
          取消
        </Button>
        <Button type="primary" onClick={() => void save()} loading={busy}>
          保存名额
        </Button>
      </Space>
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
          requestId: newRequestId(),
          schoolId,
          curriculumId: values.curriculumId,
          name: values.name,
          classType: values.classType ?? "NORMAL",
          groupTraining: values.groupTraining ?? false,
          capacity: values.capacity ?? null,
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
          label="团培班"
          name="groupTraining"
          valuePropName="checked"
          initialValue={false}
          extra="沿用旧项目的班级属性；报名、课时和账户仍按个人分别记账。"
        >
          <Switch disabled={busy} />
        </Form.Item>
        <Form.Item
          label="班型"
          name="classType"
          initialValue="NORMAL"
          extra="结算模式 0（自动）时：VIP 走平均价格，大班与普通班走课程匹配"
        >
          <Select
            disabled={busy}
            options={[
              { value: "VIP", label: "VIP" },
              { value: "LARGE", label: "大班" },
              { value: "NORMAL", label: "普通班" },
            ]}
          />
        </Form.Item>
        <Form.Item
          label="名额上限"
          name="capacity"
          extra="留空表示不限；已满班级拒绝新增学员，当前不提供候补。"
          rules={[
            {
              validator: async (_, value?: number) => {
                if (
                  value == null ||
                  (Number.isInteger(value) && value >= 0 && value <= 100000)
                )
                  return;
                throw new Error("名额上限须为 0–100000 的整数");
              },
            },
          ]}
        >
          <InputNumber min={0} max={100000} precision={0} disabled={busy} />
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
          requestId: newRequestId(),
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
