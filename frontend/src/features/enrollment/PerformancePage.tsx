import { useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { useQuery } from "@tanstack/react-query";
import { Button, Input, Select, Space, Table, Typography } from "antd";
import { api } from "../../shared/http";
import {
  LoadingBlock,
  Page,
  PageHeader,
  Panel,
  StatStrip,
  StateBlock,
  Toolbar,
} from "../../shared/ui/Page";
import type { components } from "../../shared/generated/api";

type ConsultantPerformance = components["schemas"]["ConsultantPerformance"];

function isoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function monthStart() {
  const now = new Date();
  return isoDate(new Date(now.getFullYear(), now.getMonth(), 1));
}

/** 课程顾问业绩：按报名时间汇总报名数、合同金额与课时；金额与课时都是服务端返回的十进制文本。 */
export function PerformancePage({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(() => isoDate(new Date()));

  const capabilities = useQuery({
    queryKey: ["enrollment-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/enrollment/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取报名权限");
      return data;
    },
  });

  const schools = capabilities.data?.schools ?? [];
  const effectiveSchool = schoolId ?? schools[0]?.id;

  const performance = useQuery({
    queryKey: ["consultant-performance", effectiveSchool, from, to],
    enabled: Boolean(effectiveSchool),
    queryFn: async ({ signal }): Promise<ConsultantPerformance[]> => {
      const { data, response } = await api.GET(
        "/api/enrollment/reports/consultant-performance",
        { params: { query: { schoolId: effectiveSchool!, from, to } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取顾问业绩");
      return data;
    },
  });

  if (capabilities.isPending)
    return (
      <Page>
        <PageHeader title="销售业绩" description="正在确认报名权限…" />
        <LoadingBlock>正在确认报名权限…</LoadingBlock>
      </Page>
    );
  if (capabilities.error)
    return (
      <Page>
        <PageHeader title="销售业绩" description="按课程顾问汇总报名与合同金额。" />
        <Panel>
          <StateBlock
            title="无法读取报名权限"
            hint={capabilities.error.message}
            action={
              <Button onClick={() => void capabilities.refetch()}>重试</Button>
            }
          />
        </Panel>
      </Page>
    );
  if (schools.length === 0)
    return (
      <Page>
        <PageHeader title="销售业绩" description="按课程顾问汇总报名与合同金额。" />
        <Panel>
          <StateBlock
            title="当前范围内没有学校"
            hint="请先建校并给账号分配学校范围，业绩才有数据。"
          />
        </Panel>
      </Page>
    );

  const rows = performance.data ?? [];
  const totals = rows.reduce(
    (acc, row) => ({
      enrollments: acc.enrollments + (row.enrollments ?? 0),
      amount: acc.amount + Number(row.amount ?? 0),
      hours: acc.hours + Number(row.hours ?? 0),
    }),
    { enrollments: 0, amount: 0, hours: 0 },
  );

  const columns: ColumnsType<ConsultantPerformance> = [
    { title: "课程顾问", dataIndex: "consultantName" },
    { title: "报名数", dataIndex: "enrollments" },
    {
      title: "合同金额",
      dataIndex: "amount",
      render: (value: string) => `${value} 元`,
    },
    { title: "课时", dataIndex: "hours" },
  ];

  return (
    <Page>
      <PageHeader
        title="销售业绩"
        description="按报名时间统计课程顾问的报名数、合同金额与课时；未指定顾问的报名归入“未指定顾问”。"
        actions={
          <Button
            onClick={() => void performance.refetch()}
            loading={performance.isFetching}
          >
            刷新
          </Button>
        }
      />
      <Toolbar>
        <Select
          aria-label="归属学校"
          style={{ width: 220 }}
          value={effectiveSchool}
          options={schools.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
          onChange={(value) => setSchoolId(value)}
        />
        <Space>
          <Input
            aria-label="开始日期"
            style={{ width: 130 }}
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
          <Typography.Text type="secondary">至</Typography.Text>
          <Input
            aria-label="结束日期"
            style={{ width: 130 }}
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </Space>
        <Typography.Text type="secondary">
          默认本月；金额与课时为十进制文本
        </Typography.Text>
      </Toolbar>
      <StatStrip
        items={[
          { label: "顾问数", value: rows.length },
          { label: "报名数", value: totals.enrollments },
          { label: "合同金额", value: `${totals.amount.toFixed(2)} 元` },
          { label: "课时", value: totals.hours.toFixed(2) },
        ]}
      />
      <Panel ariaLabel="顾问业绩">
        {performance.error ? (
          <StateBlock
            title="业绩加载失败"
            hint={performance.error.message}
            action={
              <Button onClick={() => void performance.refetch()}>重试</Button>
            }
          />
        ) : (
          <Table
            rowKey={(row) => row.consultantId ?? row.consultantName ?? "unknown"}
            loading={performance.isPending}
            dataSource={rows}
            columns={columns}
            locale={{ emptyText: "该期间没有报名记录" }}
            pagination={false}
          />
        )}
      </Panel>
    </Page>
  );
}
