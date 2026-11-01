import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type ResourceBlockView = components["schemas"]["ResourceBlockView"];
type SchedulePolicyView = components["schemas"]["SchedulePolicyView"];
type ResourceOption = { id?: string; name?: string };

/**
 * 排课设置：资源不可用时间与学校级策略。
 *
 * 不可用时间对应旧系统的"教师活动/休假、教室预订"；策略决定这些不可用是阻断还是只警告，
 * 以及同一教师两节课之间至少要隔多少分钟。
 */
export function SchedulingSettings({
  schoolId,
  canMaintain,
  onClose,
  onUnauthorized,
}: {
  schoolId: string;
  canMaintain: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
}) {
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const policy = useQuery({
    queryKey: ["schedule-policy", schoolId],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/schedule-policy",
        {
          params: { query: { schoolId } },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取排课策略");
      return data;
    },
  });

  return (
    <Modal
      title="排课设置"
      open
      width={720}
      onCancel={onClose}
      footer={null}
      mask={{ closable: true }}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {message ? <Alert title={message} type="success" showIcon /> : null}
      {!canMaintain ? (
        <Alert
          title="当前账号只能查看"
          description="维护不可用时间与策略需要该学校的课次维护权限。"
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
        />
      ) : null}
      <Tabs
        items={[
          {
            key: "blocks",
            label: "不可用时间",
            children: (
              <BlocksTab
                schoolId={schoolId}
                canMaintain={canMaintain}
                onError={setError}
                onMessage={setMessage}
              />
            ),
          },
          {
            key: "policy",
            label: "排课策略",
            children: (
              <PolicyTab
                schoolId={schoolId}
                canMaintain={canMaintain}
                policy={policy.data}
                onError={setError}
                onMessage={setMessage}
                onSaved={() => void policy.refetch()}
              />
            ),
          },
        ]}
      />
    </Modal>
  );
}

/** 不可用时间：新增 + 列表。 */
export function BlocksTab({
  schoolId,
  canMaintain,
  onError,
  onMessage,
}: {
  schoolId: string;
  canMaintain: boolean;
  onError: (message: string) => void;
  onMessage: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const blocks = useQuery({
    queryKey: ["resource-blocks", schoolId],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/resource-blocks",
        {
          params: { query: { schoolId } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });
  const teachers = useResourceOptions("TEACHER");
  const rooms = useResourceOptions("ROOM");

  async function save() {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/resource-blocks", {
        headers: { [token.headerName!]: token.token! },
        body: {
          schoolId,
          teacherId: values.kind === "TEACHER" ? values.resourceId : undefined,
          roomId: values.kind === "ROOM" ? values.resourceId : undefined,
          blockDate: values.blockDate,
          startsAt: values.startsAt,
          endsAt: values.endsAt,
          reason: values.reason || undefined,
        },
      });
      if (!response.ok) {
        onError("新增失败：请核对资源、日期范围与课次维护权限。");
        return;
      }
      onMessage("已新增不可用时间");
      form.resetFields();
      await blocks.refetch();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {canMaintain ? (
        <Form form={form} layout="vertical" initialValues={{ kind: "TEACHER" }}>
          <Space wrap>
            <Form.Item label="资源类型" name="kind">
              <Select
                style={{ width: 110 }}
                options={[
                  { value: "TEACHER", label: "教师" },
                  { value: "ROOM", label: "教室" },
                ]}
                disabled={busy}
              />
            </Form.Item>
            <Form.Item
              label="资源"
              name="resourceId"
              rules={[{ required: true, message: "请选择资源" }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                style={{ width: 160 }}
                options={[...(teachers.data ?? []), ...(rooms.data ?? [])].map(
                  (item) => ({
                    value: item.id!,
                    label: item.name!,
                  }),
                )}
                disabled={busy}
              />
            </Form.Item>
            <Form.Item
              label="日期"
              name="blockDate"
              rules={[{ required: true }]}
            >
              <Input
                placeholder="2026-05-02"
                maxLength={10}
                style={{ width: 120 }}
                disabled={busy}
              />
            </Form.Item>
            <Form.Item
              label="开始"
              name="startsAt"
              rules={[{ required: true }]}
            >
              <Input
                placeholder="09:00"
                maxLength={5}
                style={{ width: 80 }}
                disabled={busy}
              />
            </Form.Item>
            <Form.Item label="结束" name="endsAt" rules={[{ required: true }]}>
              <Input
                placeholder="18:00"
                maxLength={5}
                style={{ width: 80 }}
                disabled={busy}
              />
            </Form.Item>
            <Form.Item label="原因" name="reason">
              <Input maxLength={100} style={{ width: 160 }} disabled={busy} />
            </Form.Item>
            <Button
              type="primary"
              onClick={() => void save()}
              loading={busy}
              disabled={busy}
            >
              新增
            </Button>
          </Space>
        </Form>
      ) : null}
      <Table
        rowKey="id"
        size="small"
        loading={blocks.isPending}
        dataSource={blocks.data ?? []}
        pagination={false}
        columns={[
          { title: "日期", dataIndex: "blockDate" },
          {
            title: "时段",
            render: (_: unknown, block: ResourceBlockView) =>
              `${block.startsAt}-${block.endsAt}`,
          },
          {
            title: "资源",
            render: (_: unknown, block: ResourceBlockView) =>
              block.teacherId
                ? `教师：${block.teacherName}`
                : `教室：${block.roomName}`,
          },
          {
            title: "原因",
            dataIndex: "reason",
            render: (value?: string) => value ?? "—",
          },
        ]}
        locale={{ emptyText: "没有不可用时间：排课只按课程与资源占用判断" }}
      />
    </>
  );
}

/** 策略：最小间隔 + 两个"是否阻断"开关。 */
export function PolicyTab({
  schoolId,
  canMaintain,
  policy,
  onError,
  onMessage,
  onSaved,
}: {
  schoolId: string;
  canMaintain: boolean;
  policy: SchedulePolicyView | undefined;
  onError: (message: string) => void;
  onMessage: (message: string) => void;
  onSaved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();

  async function save() {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/schedule-policy", {
        params: { query: { schoolId } },
        headers: { [token.headerName!]: token.token! },
        body: {
          blockTeacherUnavailable: values.blockTeacherUnavailable,
          blockRoomUnavailable: values.blockRoomUnavailable,
          minGapMinutes: Number(values.minGapMinutes ?? 0),
          expectedVersion: policy?.version,
        },
      });
      if (response.status === 409) {
        onError("策略已被他人修改，请刷新后重试。");
        return;
      }
      if (!response.ok) {
        onError("保存失败：请核对课次维护权限。");
        return;
      }
      onMessage("策略已保存");
      onSaved();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{
        blockTeacherUnavailable: policy?.blockTeacherUnavailable ?? true,
        blockRoomUnavailable: policy?.blockRoomUnavailable ?? true,
        minGapMinutes: policy?.minGapMinutes ?? 0,
      }}
    >
      <Form.Item
        label="教师不可用（活动/休假）时"
        name="blockTeacherUnavailable"
        valuePropName="checked"
      >
        <Switch
          checkedChildren="阻断"
          unCheckedChildren="仅警告"
          disabled={!canMaintain || busy}
        />
      </Form.Item>
      <Form.Item
        label="教室被预订时"
        name="blockRoomUnavailable"
        valuePropName="checked"
      >
        <Switch
          checkedChildren="阻断"
          unCheckedChildren="仅警告"
          disabled={!canMaintain || busy}
        />
      </Form.Item>
      <Form.Item
        label="教师两节课之间最小间隔（分钟，0 表示不限制）"
        name="minGapMinutes"
      >
        <InputNumber
          min={0}
          max={240}
          step={5}
          style={{ width: 160 }}
          disabled={!canMaintain || busy}
        />
      </Form.Item>
      <Typography.Text type="secondary">
        阻断=该时段排不进去；仅警告=仍可排，但预览会标黄。
      </Typography.Text>
      {canMaintain ? (
        <div style={{ marginTop: 12 }}>
          <Button
            type="primary"
            onClick={() => void save()}
            loading={busy}
            disabled={busy}
          >
            保存策略
          </Button>
        </div>
      ) : null}
    </Form>
  );
}

function useResourceOptions(kind: "TEACHER" | "ROOM") {
  return useQuery({
    queryKey: ["resource-options", kind],
    queryFn: async ({ signal }): Promise<ResourceOption[]> => {
      const { data, response } = await api.GET(
        "/api/organization/resources/{kind}/options",
        {
          params: { path: { kind }, query: { type: "SCHOOL" } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });
}
