import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Switch,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type CustomerRecyclePolicy = components["schemas"]["CustomerRecyclePolicy"];

/**
 * 学校级客户自动回收规则编辑器。
 *
 * 单独成文件：它有自己的读取/保存与版本冲突处理，和工作台的客户列表状态无关；
 * 主文件只负责在校区可维护时挂出这个入口。
 */
export function CustomerRecyclePolicyEditor({
  schoolId,
  onClose,
  onUnauthorized,
  onUpdated,
}: {
  schoolId: string;
  onClose: () => void;
  onUnauthorized: () => void;
  onUpdated: () => void;
}) {
  const [form] = Form.useForm();
  const client = useQueryClient();
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const pending = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );
  const policy = useQuery({
    queryKey: ["customer-recycle-policy", schoolId],
    queryFn: async ({ signal }): Promise<CustomerRecyclePolicy> => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/recycle-policy",
        { params: { query: { schoolId } }, signal },
      );
      if (response.status === 401) onUnauthorized();
      if (!response.ok || !data) throw new Error("无法读取自动回收规则。");
      return data;
    },
  });
  async function save(values: {
    enabled: boolean;
    inactivityDays: number;
    assignmentAgeEnabled: boolean;
    assignmentAgeDays: number;
    firstDayNoContactEnabled: boolean;
    firstDayCutoff: string;
    reason: string;
  }) {
    if (!policy.data || saving) return;
    const fingerprintValue = JSON.stringify([
      schoolId,
      policy.data.version,
      values,
    ]);
    if (pending.current?.fingerprint !== fingerprintValue)
      pending.current = {
        fingerprint: fingerprintValue,
        requestId: crypto.randomUUID(),
      };
    setSaving(true);
    setError("");
    try {
      const headers = await csrf();
      const { data, response } = await api.PUT(
        "/api/admissions/customers/recycle-policy",
        {
          params: { query: { schoolId } },
          headers,
          body: {
            requestId: pending.current.requestId,
            expectedVersion: policy.data.version,
            ...values,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录。");
      }
      if (response.status === 409)
        throw new Error("规则已被其他人修改，请关闭后重新打开并核对。");
      if (!response.ok || !data)
        throw new Error("保存未确认，可重试相同内容。");
      pending.current = null;
      await client.invalidateQueries({
        queryKey: ["customer-recycle-policy", schoolId],
      });
      onUpdated();
    } catch (reason) {
      setError(
        reason instanceof Error
          ? reason.message
          : "保存未确认，可重试相同内容。",
      );
    } finally {
      setSaving(false);
    }
  }
  return (
    <Modal
      title="自动回收规则"
      open
      onCancel={onClose}
      footer={null}
      destroyOnHidden
    >
      {policy.error ? (
        <Alert type="error" title={policy.error.message} showIcon />
      ) : null}
      {error ? <Alert type="error" title={error} showIcon /> : null}
      {policy.data ? (
        <Form
          form={form}
          layout="vertical"
          initialValues={policy.data}
          onFinish={(values) => void save(values)}
        >
          <Form.Item
            name="enabled"
            label="启用自动回收"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="inactivityDays"
            label="未跟进回收天数"
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={365} />
          </Form.Item>
          <Form.Item
            name="assignmentAgeEnabled"
            label="启用负责人分派时限"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="assignmentAgeDays"
            label="负责人分派回收天数"
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={365} />
          </Form.Item>
          <Form.Item
            name="firstDayNoContactEnabled"
            label="启用首日未联系回收"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="firstDayCutoff"
            label="首日未联系截止时间"
            rules={[{ required: true }]}
          >
            <Input type="time" />
          </Form.Item>
          <Form.Item
            name="reason"
            label="修改原因"
            rules={[{ required: true, max: 40 }]}
          >
            <Input maxLength={40} />
          </Form.Item>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" htmlType="submit" loading={saving}>
              保存规则
            </Button>
          </Space>
        </Form>
      ) : null}
    </Modal>
  );
}
