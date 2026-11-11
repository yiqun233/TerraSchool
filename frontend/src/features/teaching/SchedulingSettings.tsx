import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
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
type SchoolClosureView = components["schemas"]["SchoolClosureView"];
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
          {
            key: "calendar",
            label: "学校校历",
            children: (
              <SchoolCalendarTab
                schoolId={schoolId}
                canMaintain={canMaintain}
                onError={setError}
                onMessage={setMessage}
              />
            ),
          },
        ]}
      />
    </Modal>
  );
}

/** 学校整日停课日：排课预览和确认会硬性跳过该日，并保留取消历史。 */
export function SchoolCalendarTab({
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
  const closures = useQuery({
    queryKey: ["school-closures", schoolId],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/school-closures",
        {
          params: { query: { schoolId } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });

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
      const { response } = await api.POST("/api/teaching/school-closures", {
        headers: { [token.headerName!]: token.token! },
        body: { schoolId, closedOn: values.closedOn, reason: values.reason },
      });
      if (!response.ok) {
        onError("保存失败：请核对日期、原因与课次维护权限。");
        return;
      }
      onMessage("已登记学校停课日");
      form.resetFields();
      await closures.refetch();
    } catch {
      onError("请求结果不确定时可用相同日期和原因安全重试。");
    } finally {
      setBusy(false);
    }
  }

  async function cancel(closure: SchoolClosureView) {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/school-closures/{id}/cancel",
        {
          params: { path: { id: closure.id! } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: closure.version! },
        },
      );
      if (!response.ok) {
        onError("取消失败：记录可能已变化，请刷新后重试。");
        return;
      }
      onMessage("已取消停课日，历史记录保留");
      await closures.refetch();
    } catch {
      onError("无法获取安全令牌或网络中断，请刷新后核对结果。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {canMaintain ? (
        <Form form={form} layout="vertical">
          <Space wrap>
            <Form.Item
              label="停课日期"
              name="closedOn"
              rules={[{ required: true, message: "请输入日期" }]}
            >
              <Input
                placeholder="2026-11-11"
                maxLength={10}
                style={{ width: 140 }}
                disabled={busy}
              />
            </Form.Item>
            <Form.Item
              label="原因"
              name="reason"
              rules={[{ required: true, message: "请填写原因" }]}
            >
              <Input maxLength={200} style={{ width: 260 }} disabled={busy} />
            </Form.Item>
            <Button
              type="primary"
              onClick={() => void save()}
              loading={busy}
              disabled={busy}
            >
              登记停课日
            </Button>
          </Space>
        </Form>
      ) : null}
      <Table
        rowKey="id"
        size="small"
        loading={closures.isPending}
        dataSource={closures.data ?? []}
        pagination={false}
        columns={[
          { title: "日期", dataIndex: "closedOn" },
          { title: "原因", dataIndex: "reason" },
          {
            title: "状态",
            dataIndex: "active",
            render: (active: boolean) => (active ? "停课" : "已取消"),
          },
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, closure: SchoolClosureView) =>
              canMaintain && closure.active ? (
                <Popconfirm
                  title="取消这个停课日？"
                  description={`${closure.closedOn} · ${closure.reason}`}
                  okText="取消停课"
                  cancelText="返回"
                  onConfirm={() => void cancel(closure)}
                >
                  <Button size="small" danger loading={busy}>
                    取消
                  </Button>
                </Popconfirm>
              ) : null,
          },
        ]}
        locale={{ emptyText: "尚无学校停课日" }}
      />
    </>
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

  async function remove(block: ResourceBlockView) {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.DELETE(
        "/api/teaching/resource-blocks/{id}",
        {
          params: { path: { id: block.id! } },
          headers: { [token.headerName!]: token.token! },
        },
      );
      if (!response.ok) {
        onError("撤销失败，请核对课次维护权限。");
        return;
      }
      onMessage("已撤销该不可用时间");
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
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, block: ResourceBlockView) =>
              canMaintain ? (
                <Popconfirm
                  title="撤销这条不可用时间？"
                  description={`${block.blockDate} ${block.startsAt}-${block.endsAt}`}
                  okText="撤销"
                  cancelText="取消"
                  onConfirm={() => void remove(block)}
                >
                  <Button size="small" danger loading={busy}>
                    撤销
                  </Button>
                </Popconfirm>
              ) : null,
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
  // 策略是异步读回来的：initialValues 只在首次挂载生效，数据晚到会让表单停在硬编码默认值，
  // 用户直接点保存就把“默认策略”当成现状写回服务端。
  useEffect(() => {
    if (!policy) return;
    form.setFieldsValue({
      blockTeacherUnavailable: policy.blockTeacherUnavailable ?? true,
      blockRoomUnavailable: policy.blockRoomUnavailable ?? true,
      minGapMinutes: policy.minGapMinutes ?? 0,
    });
  }, [policy, form]);

  async function save() {
    // 没有读到版本号时不能提交：服务端在 expectedVersion 缺失时会跳过乐观锁。
    if (!policy) {
      onError("排课策略尚未加载完成，请刷新后重试。");
      return;
    }
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
          expectedVersion: policy.version,
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
            disabled={busy || !policy}
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
