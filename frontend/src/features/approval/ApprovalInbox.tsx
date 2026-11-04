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

type ApprovalRequestView = components["schemas"]["ApprovalRequestView"];
type ApprovalPolicyView = components["schemas"]["ApprovalPolicyView"];

const ACTION_LABEL: Record<string, string> = {
  REFUND_HOURS: "退课时",
  CASH_REFUND: "现金退款",
  ENROLLMENT_DISCOUNT: "报名优惠调整",
  SERVICE_FEE_REFUND: "服务费退费",
  SERVICE_FEE_REVERSAL: "服务费退费冲正",
  ENROLLMENT_TRANSFER: "转班",
  ENROLLMENT_WITHDRAWAL: "退班",
  ENROLLMENT_DEFER: "延期",
  ENROLLMENT_REDUCTION: "报名减款",
  SERVICE_FEE_REDUCTION: "服务费减款",
};
const STATUS_LABEL: Record<string, string> = {
  PENDING: "待审批",
  APPROVED: "已通过",
  REJECTED: "已驳回",
  WITHDRAWN: "已撤回",
  EXPIRED: "已失效",
};

/**
 * 审批工作台：策略维护（免批额度）与待办审批。
 *
 * 申请人不能审批自己的单据；审批通过的单据由退费动作一次性消耗。
 */
export function ApprovalInbox({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [editing, setEditing] = useState<ApprovalPolicyView | null>(null);
  const [requesting, setRequesting] = useState(false);
  const [expiring, setExpiring] = useState<ApprovalRequestView | null>(null);
  const [error, setError] = useState("");

  const capabilities = useQuery({
    queryKey: ["approval-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/approval/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取审批权限");
      return data;
    },
  });
  const current = schoolId ?? capabilities.data?.schools?.[0]?.id;
  const school = capabilities.data?.schools?.find(
    (item) => item.id === current,
  );
  const policies = useQuery({
    queryKey: ["approval-policies", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/approval/policies", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data;
    },
  });
  const requests = useQuery({
    queryKey: ["approval-requests", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/approval/requests", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data;
    },
  });

  async function decide(request: ApprovalRequestView, decision: string) {
    setError("");
    const token = await csrf();
    const { response } = await api.POST("/api/approval/requests/{id}/decide", {
      params: { path: { id: request.id! } },
      headers: { [token.headerName!]: token.token! },
      body: { expectedVersion: request.version, decision, note: undefined },
    });
    if (response.status === 409) {
      setError("审批单已变化或不能审批自己的单据。");
    } else if (!response.ok) {
      setError("审批失败，请核对审批权限。");
    }
    await requests.refetch();
  }

  async function withdraw(request: ApprovalRequestView) {
    setError("");
    const token = await csrf();
    const { response } = await api.POST(
      "/api/approval/requests/{id}/withdraw",
      {
        params: { path: { id: request.id! } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: request.version },
      },
    );
    if (response.status === 409) {
      setError("审批单已处理或版本已变化，请刷新记录。 ");
    } else if (!response.ok) {
      setError("撤回失败，请确认这是本人提交的待审申请。 ");
    }
    await requests.refetch();
  }

  async function expire() {
    if (!expiring) return;
    const reason = window.prompt("请输入审批单失效原因")?.trim();
    if (!reason) return;
    setError("");
    const token = await csrf();
    const { response } = await api.POST("/api/approval/requests/{id}/expire", {
      params: { path: { id: expiring.id! } },
      headers: { [token.headerName!]: token.token! },
      body: { expectedVersion: expiring.version, reason },
    });
    setExpiring(null);
    if (response.status === 409) {
      setError("审批单已处理或版本已变化，请刷新记录。");
    } else if (!response.ok) {
      setError("失效操作失败，请核对审批权限和失效原因。");
    }
    await requests.refetch();
  }

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取审批权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无审批读取权限" type="error" />;
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
          onChange={(value) => setSchoolId(value)}
        />
        <Button
          onClick={() => void requests.refetch()}
          loading={requests.isFetching}
        >
          刷新待办
        </Button>
        <Button onClick={() => setRequesting(true)}>申请审批</Button>
        <Typography.Text type="secondary">
          金额低于免批额度时无需审批
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Card
        title="审批策略"
        size="small"
        extra={
          school?.canManage &&
          !(policies.data ?? []).some(
            (policy) => policy.action === "ENROLLMENT_DISCOUNT",
          ) ? (
            <Button
              size="small"
              onClick={() =>
                setEditing({
                  action: "ENROLLMENT_DISCOUNT",
                  freeBelow: "0.00",
                  active: true,
                  version: 0,
                } as ApprovalPolicyView)
              }
            >
              配置优惠审批
            </Button>
          ) : null
        }
      >
        <Table
          rowKey="action"
          size="small"
          dataSource={policies.data ?? []}
          pagination={false}
          columns={[
            {
              title: "动作",
              dataIndex: "action",
              render: (action: string) => ACTION_LABEL[action] ?? action,
            },
            { title: "免批额度（元）", dataIndex: "freeBelow" },
            {
              title: "状态",
              dataIndex: "active",
              render: (active: boolean) =>
                active ? <Tag color="green">生效</Tag> : <Tag>停用</Tag>,
            },
            {
              title: "操作",
              key: "actions",
              render: (_: unknown, policy: ApprovalPolicyView) =>
                school?.canManage ? (
                  <Button size="small" onClick={() => setEditing(policy)}>
                    调整额度
                  </Button>
                ) : null,
            },
          ]}
          locale={{ emptyText: "未配置策略：当前视为免审批（生产应显式配置）" }}
        />
      </Card>
      <Card title="审批记录" size="small" style={{ marginTop: 16 }}>
        <Table
          rowKey="id"
          size="small"
          loading={requests.isPending}
          dataSource={requests.data ?? []}
          pagination={false}
          columns={[
            {
              title: "动作",
              dataIndex: "action",
              render: (action: string) => ACTION_LABEL[action] ?? action,
            },
            { title: "金额", dataIndex: "amount" },
            { title: "原因", dataIndex: "reason" },
            {
              title: "状态",
              dataIndex: "status",
              render: (status: string) => (
                <Tag>{STATUS_LABEL[status] ?? status}</Tag>
              ),
            },
            {
              title: "操作",
              key: "actions",
              render: (_: unknown, request: ApprovalRequestView) =>
                request.status === "PENDING" &&
                request.requestedBy === capabilities.data?.currentAccountId ? (
                  <Button
                    size="small"
                    danger
                    onClick={() => void withdraw(request)}
                  >
                    撤回
                  </Button>
                ) : school?.canDecide && request.status === "PENDING" ? (
                  <Space>
                    <Button size="small" onClick={() => setExpiring(request)}>
                      设为失效
                    </Button>
                    <Button
                      size="small"
                      onClick={() => void decide(request, "APPROVED")}
                    >
                      通过
                    </Button>
                    <Button
                      size="small"
                      danger
                      onClick={() => void decide(request, "REJECTED")}
                    >
                      驳回
                    </Button>
                  </Space>
                ) : null,
            },
          ]}
          locale={{ emptyText: "没有待审批单据" }}
        />
      </Card>
      {editing && current ? (
        <PolicyEditor
          schoolId={current}
          policy={editing}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void policies.refetch();
          }}
        />
      ) : null}
      {requesting && current ? (
        <RequestEditor
          onCancel={() => setRequesting(false)}
          onSaved={() => {
            setRequesting(false);
            void requests.refetch();
          }}
          onError={setError}
        />
      ) : null}
      <Modal
        title="审批申请设为失效"
        open={!!expiring}
        onCancel={() => setExpiring(null)}
        onOk={() => void expire()}
        okText="确认失效"
        cancelText="返回"
      >
        <Typography.Paragraph>
          此操作会关闭待审申请并保留审批人和原因记录；已审批单不能执行此操作。
        </Typography.Paragraph>
      </Modal>
    </>
  );
}

function RequestEditor({
  onCancel,
  onSaved,
  onError,
}: {
  onCancel: () => void;
  onSaved: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [requestId, setRequestId] = useState(() => crypto.randomUUID());
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
      const { response } = await api.POST("/api/approval/requests", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId,
          action: values.action,
          enrollmentId: values.enrollmentId,
          amount: String(values.amount),
          reason: values.reason,
        },
      });
      if (response.status === 409) {
        onError("审批申请已变化或重复提交，请刷新列表后核对原申请。");
        return;
      } else if (!response.ok) {
        onError("申请失败，请核对业务权限、报名和审批策略。");
        return;
      }
      onSaved();
    } catch {
      onError("申请审批时网络中断，请刷新后核对申请状态。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="申请财务审批" open onCancel={onCancel} footer={null}>
      <Form
        form={form}
        layout="vertical"
        onValuesChange={() => setRequestId(crypto.randomUUID())}
      >
        <Form.Item label="动作" name="action" rules={[{ required: true }]}>
          <Select
            options={[
              {
                value: "ENROLLMENT_DISCOUNT",
                label: ACTION_LABEL.ENROLLMENT_DISCOUNT,
              },
              { value: "REFUND_HOURS", label: ACTION_LABEL.REFUND_HOURS },
              { value: "CASH_REFUND", label: ACTION_LABEL.CASH_REFUND },
              {
                value: "SERVICE_FEE_REFUND",
                label: ACTION_LABEL.SERVICE_FEE_REFUND,
              },
              {
                value: "SERVICE_FEE_REVERSAL",
                label: ACTION_LABEL.SERVICE_FEE_REVERSAL,
              },
              {
                value: "ENROLLMENT_TRANSFER",
                label: ACTION_LABEL.ENROLLMENT_TRANSFER,
              },
              {
                value: "ENROLLMENT_WITHDRAWAL",
                label: ACTION_LABEL.ENROLLMENT_WITHDRAWAL,
              },
              {
                value: "ENROLLMENT_DEFER",
                label: ACTION_LABEL.ENROLLMENT_DEFER,
              },
              {
                value: "ENROLLMENT_REDUCTION",
                label: ACTION_LABEL.ENROLLMENT_REDUCTION,
              },
              {
                value: "SERVICE_FEE_REDUCTION",
                label: ACTION_LABEL.SERVICE_FEE_REDUCTION,
              },
            ]}
          />
        </Form.Item>
        <Form.Item
          label="报名编号"
          name="enrollmentId"
          rules={[{ required: true }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          label="申请金额（元）"
          name="amount"
          rules={[{ required: true }]}
        >
          <InputNumber min={0} precision={2} style={{ width: "100%" }} />
        </Form.Item>
        <Typography.Text type="secondary">
          转班、退班、延期等非金额动作可填 0；退费和减款必须填写正数金额。
        </Typography.Text>
        <Form.Item label="原因" name="reason">
          <Input maxLength={200} />
        </Form.Item>
        <Button
          type="primary"
          loading={busy}
          disabled={busy}
          onClick={() => void save()}
        >
          提交申请
        </Button>
      </Form>
    </Modal>
  );
}

export function PolicyEditor({
  schoolId,
  policy,
  onCancel,
  onSaved,
}: {
  schoolId: string;
  policy: ApprovalPolicyView;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();

  async function save() {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/approval/policies", {
        headers: { [token.headerName!]: token.token! },
        body: {
          schoolId,
          action: policy.action,
          freeBelow: String(values.freeBelow),
          active: true,
          expectedVersion: policy.version,
        },
      });
      if (response.status === 409) {
        setError("策略已被他人修改，请刷新后重试。");
        return;
      }
      if (!response.ok) {
        setError("保存失败，请核对审批策略维护权限。");
        return;
      }
      onSaved();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`调整免批额度：${ACTION_LABEL[policy.action ?? ""] ?? policy.action}`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form
        form={form}
        layout="vertical"
        initialValues={{ freeBelow: Number(policy.freeBelow) }}
      >
        <Form.Item
          label="免批额度（元）"
          name="freeBelow"
          rules={[{ required: true, message: "请输入额度" }]}
        >
          <InputNumber
            min={0}
            precision={2}
            style={{ width: "100%" }}
            disabled={busy}
          />
        </Form.Item>
        <Typography.Text type="secondary">
          金额低于该额度免审批；填 0 表示所有金额都要审批。
        </Typography.Text>
        <Button
          type="primary"
          style={{ marginTop: 12 }}
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          保存
        </Button>
      </Form>
    </Modal>
  );
}
