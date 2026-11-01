import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type CashRefundView = components["schemas"]["CashRefundView"];
type StudentBalance = components["schemas"]["StudentBalance"];

const STATUS_LABEL: Record<string, string> = {
  PREPARED: "待支付",
  PAID: "已支付",
  CANCELLED: "已取消",
};

/**
 * 退款面板：退课时并退到账户（第一步）→ 准备现金退款并冻结 → 确认实际已付（第二步）。
 *
 * 页面只提交课时、金额与版本；冻结与核销由服务端完成，支付凭据必须人工填写。
 */
export function RefundPanel({
  enrollmentId,
  studentId,
  onUnauthorized,
  onChanged,
}: {
  enrollmentId: string;
  studentId: string;
  onUnauthorized: () => void;
  onChanged: () => void;
}) {
  const [error, setError] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [paying, setPaying] = useState<CashRefundView | null>(null);

  const balance = useQuery({
    queryKey: ["refund-balance", studentId],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/funds/balance", {
        params: { query: { studentId } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取账户余额");
      return data;
    },
  });
  const refunds = useQuery({
    queryKey: ["cash-refunds", enrollmentId],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/funds/refunds", {
        params: { query: { enrollmentId } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data;
    },
  });

  async function cancelRefund(refund: CashRefundView) {
    setError("");
    const token = await csrf();
    const { response } = await api.POST("/api/funds/refunds/{id}/cancel", {
      params: { path: { id: refund.id! } },
      headers: { [token.headerName!]: token.token! },
      body: { expectedVersion: refund.version },
    });
    if (response.status === 409) {
      setError("退款单状态已变化，请刷新后再操作。");
    } else if (!response.ok) {
      setError("取消失败，请核对现金退款权限。");
    }
    await refunds.refetch();
    await balance.refetch();
    onChanged();
  }

  return (
    <>
      <Descriptions column={3} size="small" bordered style={{ marginTop: 16 }}>
        <Descriptions.Item label="账户总额">
          {balance.data?.total ?? "—"}
        </Descriptions.Item>
        <Descriptions.Item label="冻结">
          {balance.data?.reserved ?? "—"}
        </Descriptions.Item>
        <Descriptions.Item label="可用">
          {balance.data?.available ?? "—"}
        </Descriptions.Item>
      </Descriptions>
      {error ? (
        <Alert title={error} type="error" showIcon style={{ marginTop: 8 }} />
      ) : null}
      <Space wrap style={{ marginTop: 12 }}>
        <Button onClick={() => setCancelling(true)}>退课时并退回账户</Button>
        <Button
          disabled={!balance.data || Number(balance.data.available) <= 0}
          onClick={() => setPreparing(true)}
        >
          准备现金退款
        </Button>
        <Button
          onClick={() => void refunds.refetch()}
          loading={refunds.isFetching}
        >
          刷新退款单
        </Button>
        <Typography.Text type="secondary">
          现金退出需在支付后单独确认
        </Typography.Text>
      </Space>
      <Table
        rowKey="id"
        size="small"
        style={{ marginTop: 12 }}
        dataSource={refunds.data ?? []}
        pagination={false}
        columns={[
          { title: "金额", dataIndex: "amount" },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) => (
              <Tag
                color={
                  status === "PAID"
                    ? "green"
                    : status === "PREPARED"
                      ? "blue"
                      : undefined
                }
              >
                {STATUS_LABEL[status] ?? status}
              </Tag>
            ),
          },
          { title: "凭据", dataIndex: "reference" },
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, refund: CashRefundView) =>
              refund.status === "PREPARED" ? (
                <Space>
                  <Button size="small" onClick={() => setPaying(refund)}>
                    确认已付
                  </Button>
                  <Button
                    size="small"
                    danger
                    onClick={() => void cancelRefund(refund)}
                  >
                    取消
                  </Button>
                </Space>
              ) : null,
          },
        ]}
        locale={{ emptyText: "还没有现金退款单" }}
      />
      {cancelling ? (
        <CancelHoursModal
          enrollmentId={enrollmentId}
          onCancel={() => setCancelling(false)}
          onDone={() => {
            setCancelling(false);
            void balance.refetch();
            void refunds.refetch();
            onChanged();
          }}
          onError={setError}
        />
      ) : null}
      {preparing ? (
        <PrepareRefundModal
          enrollmentId={enrollmentId}
          available={balance.data?.available ?? "0.00"}
          onCancel={() => setPreparing(false)}
          onDone={() => {
            setPreparing(false);
            void balance.refetch();
            void refunds.refetch();
            onChanged();
          }}
          onError={setError}
        />
      ) : null}
      {paying ? (
        <ConfirmPaidModal
          refund={paying}
          onCancel={() => setPaying(null)}
          onDone={() => {
            setPaying(null);
            void balance.refetch();
            void refunds.refetch();
            onChanged();
          }}
          onError={setError}
        />
      ) : null}
    </>
  );
}

export function CancelHoursModal({
  enrollmentId,
  onCancel,
  onDone,
  onError,
}: {
  enrollmentId: string;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
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
      const { response } = await api.POST(
        "/api/funds/enrollments/{id}/cancel-hours",
        {
          params: { path: { id: enrollmentId } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId: crypto.randomUUID(),
            hours: String(values.hours),
            reason: values.reason || undefined,
          },
        },
      );
      if (response.status === 409) {
        onError("可退课时不足或请求重复，请核对后再提交。");
        return;
      }
      if (!response.ok) {
        onError("退课时失败，请核对退课时权限。");
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
      title="退课时并退回账户"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          label="本次退课时"
          name="hours"
          rules={[{ required: true, message: "请输入课时" }]}
        >
          <InputNumber
            min={0.01}
            precision={2}
            style={{ width: "100%" }}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item label="原因" name="reason">
          <Input maxLength={200} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          确认退课时
        </Button>
      </Form>
    </Modal>
  );
}

export function PrepareRefundModal({
  enrollmentId,
  available,
  onCancel,
  onDone,
  onError,
}: {
  enrollmentId: string;
  available: string;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
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
      const { response } = await api.POST("/api/funds/refunds", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          enrollmentId,
          amount: String(values.amount),
        },
      });
      if (response.status === 409) {
        onError("可用余额不足或请求重复，请核对后重试。");
        return;
      }
      if (!response.ok) {
        onError("准备退款失败，请核对现金退款权限。");
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
      title="准备现金退款"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      <Typography.Text type="secondary">
        当前可用余额：{available}
      </Typography.Text>
      <Form form={form} layout="vertical">
        <Form.Item
          label="退款金额（元）"
          name="amount"
          rules={[{ required: true, message: "请输入金额" }]}
        >
          <InputNumber
            min={0.01}
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
          冻结并生成退款单
        </Button>
      </Form>
    </Modal>
  );
}

export function ConfirmPaidModal({
  refund,
  onCancel,
  onDone,
  onError,
}: {
  refund: CashRefundView;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
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
      const { response } = await api.POST("/api/funds/refunds/{id}/confirm", {
        params: { path: { id: refund.id! } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: refund.version, reference: values.reference },
      });
      if (response.status === 409) {
        onError("退款单状态已变化，请刷新后再确认。");
        return;
      }
      if (!response.ok) {
        onError("确认支付失败，请核对凭据与权限。");
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
      title={`确认已支付 ${refund.amount} 元`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      <Alert
        type="warning"
        showIcon
        title="只在现金已实际交付后确认"
        description="确认后账户扣减并记录现金退出；结果不确定时先核实单据，不要重复提交。"
      />
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Form.Item
          label="支付凭据"
          name="reference"
          rules={[
            { required: true, whitespace: true, message: "请填写凭据号" },
          ]}
        >
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          确认已付
        </Button>
      </Form>
    </Modal>
  );
}

/** 账户余额展示（测试与其他页面共用）。 */
export function BalanceView({
  balance,
}: {
  balance: StudentBalance | undefined;
}) {
  return (
    <Descriptions column={3} size="small" bordered>
      <Descriptions.Item label="总额">
        {balance?.total ?? "—"}
      </Descriptions.Item>
      <Descriptions.Item label="冻结">
        {balance?.reserved ?? "—"}
      </Descriptions.Item>
      <Descriptions.Item label="可用">
        {balance?.available ?? "—"}
      </Descriptions.Item>
    </Descriptions>
  );
}
