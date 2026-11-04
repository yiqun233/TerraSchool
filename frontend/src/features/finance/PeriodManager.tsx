import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type PeriodView = components["schemas"]["PeriodView"];
type FundsReconciliation = components["schemas"]["FundsReconciliation"];
type ReceiptDailyTotal = components["schemas"]["ReceiptDailyTotal"];

const YEAR = new Date().getFullYear();

/**
 * 财务期间：按自然月锁定/解锁。
 *
 * 锁定后该月的收款、分配、退费与课次消费都会被服务端拒绝；页面只负责发起，不会预先过滤数据。
 */
export function PeriodManager({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [year, setYear] = useState(String(YEAR));
  const [target, setTarget] = useState<{
    period: PeriodView;
    lock: boolean;
  } | null>(null);
  const [error, setError] = useState("");
  const [receiptRange, setReceiptRange] = useState(() => {
    const today = new Date();
    const year = today.getFullYear();
    const month = String(today.getMonth() + 1).padStart(2, "0");
    const end = new Date(year, today.getMonth() + 1, 0).getDate();
    return { from: `${year}-${month}-01`, to: `${year}-${month}-${end}` };
  });

  const capabilities = useQuery({
    queryKey: ["finance-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/finance/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取期间权限");
      return data;
    },
  });
  const current = schoolId ?? capabilities.data?.schools?.[0]?.id;
  const school = capabilities.data?.schools?.find(
    (item) => item.id === current,
  );
  const periods = useQuery({
    queryKey: ["finance-periods", current, year],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/finance/periods", {
        params: { query: { schoolId: current!, year } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data;
    },
  });
  const reconciliation = useQuery<FundsReconciliation | null>({
    queryKey: ["finance-funds-reconciliation", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/finance/reports/funds-reconciliation",
        { params: { query: { schoolId: current! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (response.status === 403) return null;
      if (!response.ok || !data) throw new Error("无法读取资金总账对账");
      return data;
    },
  });
  const receiptTotals = useQuery<ReceiptDailyTotal[] | null>({
    queryKey: [
      "finance-receipt-daily",
      current,
      receiptRange.from,
      receiptRange.to,
    ],
    enabled: !!current && receiptRange.from <= receiptRange.to,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/finance/reports/receipt-daily",
        {
          params: {
            query: {
              schoolId: current!,
              from: receiptRange.from,
              to: receiptRange.to,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (response.status === 403) return null;
      if (!response.ok || !data) throw new Error("无法读取收款统计");
      return data;
    },
  });

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取期间权限…</p>;
  const schools = capabilities.data?.schools ?? [];
  if (schools.length === 0)
    return <Alert title="当前范围内没有可查看的学校" type="warning" />;
  const locked = (periods.data ?? []).filter(
    (item) => item.status === "LOCKED",
  );

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
        <Select
          aria-label="年份"
          style={{ width: 120 }}
          value={year}
          options={[YEAR - 1, YEAR, YEAR + 1].map((item) => ({
            value: String(item),
            label: `${item} 年`,
          }))}
          onChange={(value) => setYear(value)}
        />
        <Button
          onClick={() => void periods.refetch()}
          loading={periods.isFetching}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">
          已锁定 {locked.length} 个月；锁定后该月资金与消费事实不可写入
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="锁定范围">
          收款、分配、释放、退课时、现金退款与课次关闭；按业务日期归属自然月
        </Descriptions.Item>
      </Descriptions>
      {reconciliation.error ? (
        <Alert
          type="error"
          title={reconciliation.error.message}
          showIcon
          style={{ marginTop: 12 }}
        />
      ) : null}
      {reconciliation.data ? (
        <Card
          title="资金总账对账（累计）"
          size="small"
          loading={reconciliation.isFetching}
          style={{ marginTop: 12 }}
        >
          <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
            <Descriptions.Item label="期初余额">
              {reconciliation.data.openingAmount}
            </Descriptions.Item>
            <Descriptions.Item label="净收款">
              {reconciliation.data.netReceiptAmount}
            </Descriptions.Item>
            <Descriptions.Item label="现金退款">
              {reconciliation.data.cashRefundAmount}
            </Descriptions.Item>
            <Descriptions.Item label="有效分配">
              {reconciliation.data.activeAllocatedAmount}
            </Descriptions.Item>
            <Descriptions.Item label="学员账户余额">
              {reconciliation.data.accountBalanceAmount}
            </Descriptions.Item>
            <Descriptions.Item label="对账差额">
              <Typography.Text
                type={
                  reconciliation.data.differenceAmount === "0.00"
                    ? "success"
                    : "danger"
                }
              >
                {reconciliation.data.differenceAmount}
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
          <Typography.Text type="secondary">
            核对式：期初余额＋净收款－现金退款＝有效分配＋学员账户余额；该数值为全量累计，不按所选年份或财务期间筛选。
          </Typography.Text>
        </Card>
      ) : null}
      <Card
        title="按日收款统计（单据原额）"
        size="small"
        loading={receiptTotals.isFetching}
        style={{ marginTop: 12 }}
      >
        <Space className="toolbar" wrap>
          <label htmlFor="receipt-report-from">开始日期</label>
          <Input
            id="receipt-report-from"
            aria-label="收款统计开始日期"
            type="date"
            value={receiptRange.from}
            onChange={(event) =>
              setReceiptRange((range) => ({
                ...range,
                from: event.target.value,
              }))
            }
          />
          <label htmlFor="receipt-report-to">结束日期</label>
          <Input
            id="receipt-report-to"
            aria-label="收款统计结束日期"
            type="date"
            value={receiptRange.to}
            onChange={(event) =>
              setReceiptRange((range) => ({ ...range, to: event.target.value }))
            }
          />
        </Space>
        {receiptRange.from > receiptRange.to ? (
          <Alert
            type="warning"
            title="结束日期不能早于开始日期"
            showIcon
            style={{ marginTop: 12 }}
          />
        ) : null}
        {receiptTotals.error ? (
          <Alert
            type="error"
            title={receiptTotals.error.message}
            showIcon
            style={{ marginTop: 12 }}
          />
        ) : receiptTotals.data === null ? (
          <Typography.Text type="secondary">
            当前账号没有该校收款统计权限
          </Typography.Text>
        ) : (
          <Table
            style={{ marginTop: 12 }}
            size="small"
            rowKey={(row) => `${row.day}-${row.method}-${row.status}`}
            pagination={false}
            dataSource={receiptTotals.data ?? []}
            columns={[
              { title: "发生日", dataIndex: "day" },
              { title: "方式", dataIndex: "method" },
              {
                title: "单据状态",
                dataIndex: "status",
                render: (status: string) =>
                  status === "VOIDED" ? "已撤销" : "已确认",
              },
              { title: "单据数", dataIndex: "receiptCount" },
              { title: "收款单原额", dataIndex: "grossAmount" },
            ]}
          />
        )}
        <Typography.Text type="secondary">
          已撤销单据单独列示；统计金额为收款单原额，不含现金退款净额或财务确收口径。
        </Typography.Text>
      </Card>
      <Table
        rowKey="period"
        size="small"
        loading={periods.isPending}
        dataSource={periods.data ?? []}
        pagination={false}
        columns={[
          { title: "期间", dataIndex: "period" },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) =>
              status === "LOCKED" ? (
                <Tag color="red">已锁定</Tag>
              ) : (
                <Tag color="green">开放</Tag>
              ),
          },
          {
            title: "锁定人",
            dataIndex: "lockedBy",
            render: (value?: string) => value ?? "—",
          },
          {
            title: "原因",
            dataIndex: "reason",
            render: (value?: string) => value ?? "—",
          },
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, period: PeriodView) =>
              (
                period.status === "LOCKED" ? school?.canUnlock : school?.canLock
              ) ? (
                <Button
                  size="small"
                  danger={period.status !== "LOCKED"}
                  onClick={() =>
                    setTarget({ period, lock: period.status !== "LOCKED" })
                  }
                >
                  {period.status === "LOCKED" ? "解锁" : "锁定"}
                </Button>
              ) : null,
          },
        ]}
      />
      {target && current ? (
        <PeriodActionModal
          schoolId={current}
          period={target.period}
          lock={target.lock}
          onCancel={() => setTarget(null)}
          onDone={() => {
            setTarget(null);
            void periods.refetch();
          }}
          onError={setError}
        />
      ) : null}
    </>
  );
}

export function PeriodActionModal({
  schoolId,
  period,
  lock,
  onCancel,
  onDone,
  onError,
}: {
  schoolId: string;
  period: PeriodView;
  lock: boolean;
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
      const path = lock
        ? "/api/finance/periods/{period}/lock"
        : "/api/finance/periods/{period}/unlock";
      const request = lock
        ? api.POST("/api/finance/periods/{period}/lock", {
            params: { path: { period: period.period! } },
            headers: { [token.headerName!]: token.token! },
            body: {
              schoolId,
              period: period.period!,
              reason: values.reason || undefined,
            },
          })
        : api.POST("/api/finance/periods/{period}/unlock", {
            params: { path: { period: period.period! } },
            headers: { [token.headerName!]: token.token! },
            body: {
              schoolId,
              period: period.period!,
              reason: values.reason || undefined,
              expectedVersion: period.version,
            },
          });
      const { response } = await request;
      if (response.status === 409) {
        onError(
          lock
            ? "锁定失败：该期间状态已变化，请刷新后重试。"
            : "解锁失败：版本已变化或该期间并未锁定，请刷新后重试。",
        );
        return;
      }
      if (!response.ok) {
        onError(`操作失败（${path}），请核对锁月权限。`);
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
      title={`${lock ? "锁定" : "解锁"} ${period.period}`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      <Alert
        type={lock ? "warning" : "info"}
        showIcon
        title={lock ? "锁定后该月不可再写入" : "解锁会重新允许写入该月"}
        description={
          lock
            ? "收款、分配、释放、退课时、现金退款与课次关闭都会被拒绝；解锁需要重新确认。"
            : "请确认已核对账目；解锁操作会记录审计。"
        }
      />
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Form.Item
          label="原因"
          name="reason"
          rules={[{ required: true, whitespace: true, message: "请填写原因" }]}
        >
          <Input maxLength={200} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          danger={lock}
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          {lock ? "确认锁定" : "确认解锁"}
        </Button>
      </Form>
    </Modal>
  );
}
