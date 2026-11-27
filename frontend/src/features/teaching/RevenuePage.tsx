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

type RevenueRow = components["schemas"]["RevenueRow"];
type RevenueDetail = components["schemas"]["RevenueDetail"];

function isoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function monthStart() {
  const now = new Date();
  return isoDate(new Date(now.getFullYear(), now.getMonth(), 1));
}

export function RevenuePage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [schoolId, setSchoolId] = useState<string>();
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(() => isoDate(new Date()));
  const [selected, setSelected] = useState<{ classId: string; className: string }>();

  const capabilities = useQuery({
    queryKey: ["teaching-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/capabilities", { signal });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取教务权限");
      return data;
    },
  });

  const schools = capabilities.data?.schools ?? [];
  const effectiveSchool = schoolId ?? schools[0]?.id;

  const revenue = useQuery({
    queryKey: ["revenue", effectiveSchool, from, to],
    enabled: Boolean(effectiveSchool),
    queryFn: async ({ signal }): Promise<RevenueRow[]> => {
      const { data, response } = await api.GET("/api/teaching/reports/revenue", {
        params: { query: { schoolId: effectiveSchool!, from, to } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取收入");
      return data;
    },
  });

  const details = useQuery({
    queryKey: ["revenue-details", effectiveSchool, selected?.classId, from, to],
    enabled: Boolean(effectiveSchool && selected?.classId),
    queryFn: async ({ signal }): Promise<RevenueDetail[]> => {
      const { data, response } = await api.GET("/api/teaching/reports/revenue/details", {
        params: {
          query: { schoolId: effectiveSchool!, classId: selected!.classId, from, to },
        },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取明细");
      return data;
    },
  });

  const rows = revenue.data ?? [];
  const totals = rows.reduce<{ lessons: number; hours: number; amount: number }>(
    (acc, row) => ({
      lessons: acc.lessons + (row.lessons ?? 0),
      hours: acc.hours + Number(row.hours ?? 0),
      amount: acc.amount + Number(row.amount ?? 0),
    }),
    { lessons: 0, hours: 0, amount: 0 },
  );

  const summaryColumns: ColumnsType<RevenueRow> = [
    { title: "班级", dataIndex: "className", width: 180 },
    { title: "课程", dataIndex: "curriculumName", width: 140, render: (v) => v || "按总课时" },
    { title: "课次", dataIndex: "lessons", width: 90, align: "right" },
    {
      title: "课时",
      dataIndex: "hours",
      width: 100,
      align: "right",
      render: (value) => Number(value ?? 0).toFixed(2),
    },
    {
      title: "金额",
      dataIndex: "amount",
      width: 120,
      align: "right",
      render: (value) => Number(value ?? 0).toFixed(2),
    },
    {
      title: "",
      width: 80,
      render: (_, row) => (
        <Button
          type="link"
          onClick={() => setSelected({ classId: row.classId!, className: row.className ?? "" })}
        >
          下钻
        </Button>
      ),
    },
  ];

  const detailColumns: ColumnsType<RevenueDetail> = [
    { title: "日期", dataIndex: "lessonDate", width: 110 },
    { title: "班级", dataIndex: "className", width: 150 },
    { title: "学员", dataIndex: "studentName", width: 100 },
    { title: "课程", dataIndex: "curriculumName", width: 120, render: (v) => v || "按总课时" },
    {
      title: "课时",
      dataIndex: "hours",
      width: 80,
      align: "right",
      render: (value) => Number(value ?? 0).toFixed(2),
    },
    {
      title: "金额",
      dataIndex: "amount",
      width: 100,
      align: "right",
      render: (value) => Number(value ?? 0).toFixed(2),
    },
  ];

  if (capabilities.isLoading) return <LoadingBlock>正在读取教务权限</LoadingBlock>;

  return (
    <Page>
      <PageHeader
        title="收入下钻"
        description="只统计已关课固化的消费：班级 × 课程汇总，点“下钻”看逐课次学员明细。"
      />
      <Toolbar>
        <Space wrap>
          <Select
            style={{ width: 220 }}
            value={effectiveSchool}
            onChange={setSchoolId}
            options={schools.map((school) => ({ value: school.id!, label: school.name }))}
            placeholder="选择学校"
          />
          <Input style={{ width: 140 }} value={from} onChange={(event) => setFrom(event.target.value)} />
          <Input style={{ width: 140 }} value={to} onChange={(event) => setTo(event.target.value)} />
          <Button onClick={() => void revenue.refetch()}>刷新</Button>
        </Space>
      </Toolbar>

      <StatStrip
        items={[
          { label: "课次", value: totals.lessons },
          { label: "课时", value: totals.hours.toFixed(2) },
          { label: "金额", value: totals.amount.toFixed(2) },
        ]}
      />

      <Panel head="按班级 × 课程">
        {revenue.isLoading ? (
          <LoadingBlock>正在汇总</LoadingBlock>
        ) : revenue.isError ? (
          <StateBlock title="汇总失败" hint="请检查日期范围（一次最多 31 天）。" />
        ) : rows.length === 0 ? (
          <StateBlock title="暂无收入" hint="该日期段内没有已关课的消费。" />
        ) : (
          <Table
            rowKey={(row) => `${row.classId}-${row.curriculumId ?? "total"}`}
            size="small"
            columns={summaryColumns}
            dataSource={rows}
            pagination={false}
          />
        )}
      </Panel>

      {selected ? (
        <Panel
          head={`${selected.className} · 消费明细`}
          headEnd={<Button onClick={() => setSelected(undefined)}>关闭</Button>}
        >
          {details.isLoading ? (
            <LoadingBlock>正在读取明细</LoadingBlock>
          ) : (details.data ?? []).length === 0 ? (
            <StateBlock title="暂无明细" hint="该班在该日期段内没有消费记录。" />
          ) : (
            <Table
              rowKey={(row) => `${row.lessonId}-${row.studentName}`}
              size="small"
              columns={detailColumns}
              dataSource={details.data ?? []}
              pagination={{ pageSize: 50 }}
            />
          )}
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            模式 1/2/4 的消费不区分课程，课程列显示“按总课时”。
          </Typography.Paragraph>
        </Panel>
      ) : null}
    </Page>
  );
}
