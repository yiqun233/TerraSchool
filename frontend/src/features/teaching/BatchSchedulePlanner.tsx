import { useState } from "react";
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import { SchedulePreviewCard } from "./SchedulePlanner";

type ClassView = components["schemas"]["ClassView"];
type BatchRequest = components["schemas"]["ScheduleBatchRequest"];
type BatchPreview = components["schemas"]["ScheduleBatchPreview"];

const WEEKDAYS = [
  { value: 1, label: "周一" },
  { value: 2, label: "周二" },
  { value: 3, label: "周三" },
  { value: 4, label: "周四" },
  { value: 5, label: "周五" },
  { value: 6, label: "周六" },
  { value: 7, label: "周日" },
];

export function BatchSchedulePlanner({
  classes,
  onClose,
  onScheduled,
  onUnauthorized,
}: {
  classes: ClassView[];
  onClose: () => void;
  onScheduled: () => void;
  onUnauthorized: () => void;
}) {
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [request, setRequest] = useState<BatchRequest | null>(null);
  const [preview, setPreview] = useState<BatchPreview | null>(null);

  async function buildRequest(): Promise<BatchRequest | null> {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return null;
    }
    if (values.classIds.length < 2) {
      setError("至少选择两个班级进行联合排课。");
      return null;
    }
    const rule: components["schemas"]["ScheduleRule"] = {
      startDate: values.startDate,
      endDate: values.endDate,
      weekdays: values.weekdays,
      startsAt: values.startsAt,
      endsAt: values.endsAt,
      hours: String(values.hours),
      autoAssignResources: true,
    };
    return {
      items: values.classIds.map((classId: string) => ({ classId, rule })),
    };
  }

  async function runPreview() {
    const body = await buildRequest();
    if (!body) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/teaching/schedule/batch/preview",
        {
          headers: { [token.headerName!]: token.token! },
          body,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) {
        setError("联合预览失败，请检查日期、时段和权限。");
        return;
      }
      setRequest(body);
      setPreview(data);
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (
      !request ||
      !preview ||
      (preview.classes ?? []).every((item) => (item.available ?? 0) === 0)
    )
      return;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/teaching/schedule/batch/confirm",
        {
          headers: { [token.headerName!]: token.token! },
          body: request,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) {
        setError("联合排课确认失败，整批操作已拒绝，请刷新后重试。");
        return;
      }
      setMessage(
        `已生成 ${data.created ?? 0} 个草稿课次，跳过 ${data.skipped ?? 0} 个时段。`,
      );
      setRequest(null);
      setPreview(null);
      onScheduled();
    } catch {
      setError("网络中断，联合排课结果未确认，请刷新各班课次核对。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="多班自动排课"
      open
      width={980}
      onCancel={onClose}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {message ? <Alert title={message} type="success" showIcon /> : null}
      <Form
        form={form}
        layout="vertical"
        initialValues={{ startsAt: "09:00", endsAt: "10:00", hours: 1 }}
        onValuesChange={() => {
          setRequest(null);
          setPreview(null);
        }}
      >
        <Form.Item
          label="班级（至少两个）"
          name="classIds"
          rules={[{ required: true, message: "请选择班级" }]}
        >
          <Select
            mode="multiple"
            showSearch
            optionFilterProp="label"
            placeholder="选择要联合安排的班级"
            options={classes
              .filter((item) => item.id && item.status === "ACTIVE")
              .map((item) => ({
                value: item.id!,
                label: item.name ?? item.id!,
              }))}
            disabled={busy}
          />
        </Form.Item>
        <Space wrap>
          <Form.Item
            label="开始日期"
            name="startDate"
            rules={[{ required: true }]}
          >
            <Input
              placeholder="2026-05-02"
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
              placeholder="选择周几"
              options={WEEKDAYS}
              style={{ minWidth: 190 }}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item
            label="开始时间"
            name="startsAt"
            rules={[{ required: true }]}
          >
            <Input maxLength={5} style={{ width: 100 }} disabled={busy} />
          </Form.Item>
          <Form.Item
            label="结束时间"
            name="endsAt"
            rules={[{ required: true }]}
          >
            <Input maxLength={5} style={{ width: 100 }} disabled={busy} />
          </Form.Item>
          <Form.Item label="每次课时" name="hours" rules={[{ required: true }]}>
            <InputNumber
              min={0.01}
              precision={2}
              style={{ width: 110 }}
              disabled={busy}
            />
          </Form.Item>
        </Space>
        <Space>
          <Button
            onClick={() => void runPreview()}
            loading={busy}
            disabled={busy}
          >
            联合预览
          </Button>
          <Button
            type="primary"
            onClick={() => void confirm()}
            loading={busy}
            disabled={
              busy ||
              !preview ||
              (preview.classes ?? []).every(
                (item) => (item.available ?? 0) === 0,
              )
            }
          >
            确认整批排课
          </Button>
          <Typography.Text type="secondary">
            系统按班级顺序协调资源；冲突日期按预览跳过，写入异常时整批回滚。
          </Typography.Text>
        </Space>
      </Form>
      {(preview?.classes ?? []).map((item) => {
        const className =
          classes.find((row) => row.id === item.classId)?.name ??
          item.classId ??
          "班级";
        return (
          <div key={item.classId} style={{ marginTop: 12 }}>
            <Typography.Title level={5}>{className}</Typography.Title>
            <SchedulePreviewCard preview={item as never} />
          </div>
        );
      })}
    </Modal>
  );
}
