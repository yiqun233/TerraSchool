import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Form,
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
};
const STATUS_LABEL: Record<string, string> = {
  PENDING: "待审批",
  APPROVED: "已通过",
  REJECTED: "已驳回",
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
        params: { query: { schoolId: current!, status: "PENDING" } },
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
        <Typography.Text type="secondary">
          金额低于免批额度时无需审批
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Card title="审批策略" size="small">
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
      <Card title="待我审批" size="small" style={{ marginTop: 16 }}>
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
                school?.canDecide ? (
                  <Space>
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
    </>
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
