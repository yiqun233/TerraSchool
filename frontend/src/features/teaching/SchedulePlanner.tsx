import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type SchedulePreview = components["schemas"]["SchedulePreview"];
type ScheduleSlot = components["schemas"]["ScheduleSlot"];

const WEEKDAYS = [
  { value: 1, label: "周一" },
  { value: 2, label: "周二" },
  { value: 3, label: "周三" },
  { value: 4, label: "周四" },
  { value: 5, label: "周五" },
  { value: 6, label: "周六" },
  { value: 7, label: "周日" },
];

const REASON_LABEL: Record<string, string> = {
  DUPLICATE: "该时段已存在课次",
  TEACHER_UNAVAILABLE: "教师该时段不可用（活动/休假）",
  ROOM_UNAVAILABLE: "教室该时段被预订",
  TEACHER_MIN_GAP: "与教师上一节课间隔不足",
  LESSON_OVERLAP: "同班时间重叠",
  TEACHER_CLASH: "教师已有课",
  ROOM_CLASH: "教室已占用",
};

/**
 * 自动排课：按周几与固定时段生成课次。
 *
 * 先预览（不写库），确认后只把可用时段写为草稿课次；冲突时段给出原因，由教务决定是否换时间。
 */
export function SchedulePlanner({
  classId,
  onClose,
  onScheduled,
  onUnauthorized,
}: {
  classId: string;
  onClose: () => void;
  onScheduled: () => void;
  onUnauthorized: () => void;
}) {
  const [preview, setPreview] = useState<SchedulePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [form] = Form.useForm();

  const teachers = useQuery({
    queryKey: ["schedule-teachers"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/resources/{kind}/options",
        {
          params: { path: { kind: "TEACHER" }, query: { type: "SCHOOL" } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });
  const rooms = useQuery({
    queryKey: ["schedule-rooms"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/resources/{kind}/options",
        {
          params: { path: { kind: "ROOM" }, query: { type: "SCHOOL" } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });

  async function buildRule(): Promise<
    components["schemas"]["ScheduleRule"] | null
  > {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return null;
    }
    return {
      startDate: values.startDate,
      endDate: values.endDate,
      weekdays: values.weekdays,
      startsAt: values.startsAt,
      endsAt: values.endsAt,
      hours: String(values.hours),
      teacherId: values.teacherId || undefined,
      roomId: values.roomId || undefined,
      teacherName: undefined,
      roomName: undefined,
      topic: values.topic || undefined,
    };
  }

  async function runPreview() {
    const rule = await buildRule();
    if (!rule) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/teaching/classes/{id}/schedule/preview",
        {
          params: { path: { id: classId } },
          headers: { [token.headerName!]: token.token! },
          body: rule,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (response.status === 409) {
        setError("排课规则不合法：请检查日期范围、周几与时段。");
        return;
      }
      if (!response.ok || !data) {
        setError("预览失败，请核对排课权限。");
        return;
      }
      setPreview(data);
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  async function runConfirm() {
    const rule = await buildRule();
    if (!rule) return;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/teaching/classes/{id}/schedule/confirm",
        {
          params: { path: { id: classId } },
          headers: { [token.headerName!]: token.token! },
          body: rule,
        },
      );
      if (!response.ok || !data) {
        setError("确认排课失败，请核对排课权限或稍后重试。");
        return;
      }
      setMessage(
        `已生成 ${data.created} 个草稿课次，跳过 ${data.skipped} 个时段。`,
      );
      setPreview(null);
      onScheduled();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="自动排课"
      open
      width={760}
      onCancel={onClose}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {message ? <Alert title={message} type="success" showIcon /> : null}
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Space wrap>
          <Form.Item
            label="开始日期"
            name="startDate"
            rules={[{ required: true }]}
          >
            <Input
              placeholder="2026-05-02"
              maxLength={10}
              style={{ width: 140 }}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item
            label="结束日期"
            name="endDate"
            rules={[{ required: true }]}
          >
            <Input
              placeholder="2026-05-23"
              maxLength={10}
              style={{ width: 140 }}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item
            label="每周"
            name="weekdays"
            rules={[{ required: true, message: "选择周几" }]}
          >
            <Select
              mode="multiple"
              style={{ minWidth: 220 }}
              options={WEEKDAYS}
              disabled={busy}
              placeholder="选择周几"
            />
          </Form.Item>
        </Space>
        <Space wrap>
          <Form.Item
            label="开始时间"
            name="startsAt"
            rules={[{ required: true }]}
          >
            <Input
              placeholder="09:00"
              maxLength={5}
              style={{ width: 100 }}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item
            label="结束时间"
            name="endsAt"
            rules={[{ required: true }]}
          >
            <Input
              placeholder="10:00"
              maxLength={5}
              style={{ width: 100 }}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item
            label="每次课时"
            name="hours"
            rules={[{ required: true }]}
            initialValue={1}
          >
            <InputNumber
              min={0.01}
              precision={2}
              style={{ width: 110 }}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item label="教师" name="teacherId">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              style={{ width: 150 }}
              options={(teachers.data ?? []).map((item) => ({
                value: item.id,
                label: item.name,
              }))}
              placeholder="可不选"
              disabled={busy}
            />
          </Form.Item>
          <Form.Item label="教室" name="roomId">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              style={{ width: 150 }}
              options={(rooms.data ?? []).map((item) => ({
                value: item.id,
                label: item.name,
              }))}
              placeholder="可不选"
              disabled={busy}
            />
          </Form.Item>
        </Space>
        <Form.Item label="主题" name="topic">
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Space>
          <Button
            onClick={() => void runPreview()}
            loading={busy}
            disabled={busy}
          >
            预览
          </Button>
          <Button
            type="primary"
            onClick={() => void runConfirm()}
            loading={busy}
            disabled={busy || !preview || preview.available === 0}
          >
            确认生成
          </Button>
          <Typography.Text type="secondary">
            确认后生成的是草稿课次，发布仍走原流程
          </Typography.Text>
        </Space>
      </Form>
      {preview ? <SchedulePreviewCard preview={preview} /> : null}
    </Modal>
  );
}

/** 预览卡片：可排数量、跳过数量与逐时段原因（单独导出便于测试）。 */
export function SchedulePreviewCard({ preview }: { preview: SchedulePreview }) {
  return (
    <Card size="small" title="预览" style={{ marginTop: 12 }}>
      <Space wrap>
        <Tag color="blue">可排 {preview.available}</Tag>
        <Tag color="red">跳过 {preview.skipped}</Tag>
        <Typography.Text type="secondary">
          共 {preview.total} 个候选时段
        </Typography.Text>
      </Space>
      <Table
        rowKey="lessonDate"
        size="small"
        style={{ marginTop: 8 }}
        dataSource={preview.slots ?? []}
        pagination={false}
        columns={[
          { title: "日期", dataIndex: "lessonDate" },
          {
            title: "时段",
            render: (_: unknown, slot: ScheduleSlot) =>
              `${slot.startsAt}-${slot.endsAt}`,
          },
          {
            title: "结果",
            dataIndex: "available",
            render: (available: boolean) =>
              available ? (
                <Tag color="green">可排</Tag>
              ) : (
                <Tag color="red">跳过</Tag>
              ),
          },
          {
            title: "原因",
            dataIndex: "reason",
            render: (reason?: string) =>
              reason ? (REASON_LABEL[reason] ?? reason) : "—",
          },
        ]}
      />
    </Card>
  );
}
