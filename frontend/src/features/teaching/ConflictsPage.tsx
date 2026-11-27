import { useMemo, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { useQuery } from "@tanstack/react-query";
import { Button, Input, Select, Space, Table, Tag, Typography } from "antd";
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

type Conflict = components["schemas"]["SchoolConflict"];

const TYPES = [
  { value: "", label: "全部类型" },
  { value: "CLASS_OVERLAP", label: "同班重叠" },
  { value: "TEACHER_CLASH", label: "教师冲突" },
  { value: "ROOM_CLASH", label: "教室冲突" },
  { value: "STUDENT_CLASH", label: "学员冲突" },
  { value: "SCHOOL_CLOSED", label: "学校停课" },
  { value: "TEACHER_UNAVAILABLE", label: "教师不可用" },
  { value: "ROOM_UNAVAILABLE", label: "教室不可用" },
];

const TYPE_LABEL: Record<string, string> = Object.fromEntries(
  TYPES.filter((item) => item.value).map((item) => [item.value, item.label]),
);

function isoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function weekStart(offsetWeeks: number) {
  const today = new Date();
  today.setHours(12, 0, 0, 0);
  const day = today.getDay();
  const monday = new Date(today);
  monday.setDate(today.getDate() + (day === 0 ? -6 : 1 - day) + offsetWeeks * 7);
  return monday;
}

function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

export function ConflictsPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [schoolId, setSchoolId] = useState<string>();
  const [from, setFrom] = useState(() => isoDate(weekStart(0)));
  const [to, setTo] = useState(() => isoDate(addDays(weekStart(0), 6)));
  const [type, setType] = useState("");

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

  const conflicts = useQuery({
    queryKey: ["school-conflicts", effectiveSchool, from, to, type],
    enabled: Boolean(effectiveSchool && from && to),
    queryFn: async ({ signal }): Promise<Conflict[]> => {
      const { data, response } = await api.GET("/api/teaching/conflicts", {
        params: { query: { schoolId: effectiveSchool!, from, to, type: type || undefined } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取冲突");
      return data;
    },
  });

  const rows = conflicts.data ?? [];
  const counts = useMemo(() => {
    const byType = new Map<string, number>();
    for (const row of rows) byType.set(row.type ?? "", (byType.get(row.type ?? "") ?? 0) + 1);
    return byType;
  }, [rows]);

  const columns: ColumnsType<Conflict> = [
    { title: "日期", dataIndex: "lessonDate", width: 110 },
    {
      title: "时间",
      width: 120,
      render: (_, row) => `${row.startsAt ?? ""}–${row.endsAt ?? ""}`,
    },
    { title: "班级", dataIndex: "className", width: 150 },
    { title: "教师", dataIndex: "teacherName", width: 100, render: (v) => v || "—" },
    { title: "教室", dataIndex: "roomName", width: 100, render: (v) => v || "—" },
    {
      title: "类型",
      dataIndex: "type",
      width: 120,
      render: (value: string) => <Tag color="red">{TYPE_LABEL[value] ?? value}</Tag>,
    },
    { title: "说明", dataIndex: "message" },
    {
      title: "冲突课次",
      width: 220,
      render: (_, row) =>
        row.otherLessonId ? (
          <Typography.Text type="secondary">
            {row.otherClassName} {row.otherLessonDate} {row.otherStartsAt}
          </Typography.Text>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
  ];

  if (capabilities.isLoading) return <LoadingBlock>正在读取教务权限</LoadingBlock>;
  if (capabilities.isError)
    return (
      <Page>
        <PageHeader title="冲突中心" description="全校课次冲突检测" />
        <StateBlock title="无法读取权限" hint="请稍后重试或联系管理员。" />
      </Page>
    );

  return (
    <Page>
      <PageHeader
        title="冲突中心"
        description="全校课次冲突：同班、教师、教室、学员、停课与资源不可用，一次算完。"
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
          <Input style={{ width: 140 }} value={from} onChange={(event) => setFrom(event.target.value)} placeholder="开始日期 YYYY-MM-DD" />
          <Input style={{ width: 140 }} value={to} onChange={(event) => setTo(event.target.value)} placeholder="结束日期 YYYY-MM-DD" />
          <Select style={{ width: 150 }} value={type} onChange={setType} options={TYPES} />
          <Button onClick={() => void conflicts.refetch()}>刷新</Button>
        </Space>
      </Toolbar>

      <StatStrip
        items={[
          { label: "冲突总数", value: rows.length },
          ...TYPES.filter((item) => item.value).map((item) => ({
            label: item.label,
            value: counts.get(item.value) ?? 0,
          })),
        ]}
      />

      <Panel head="冲突明细">
        {conflicts.isLoading ? (
          <LoadingBlock>正在检测</LoadingBlock>
        ) : conflicts.isError ? (
          <StateBlock title="检测失败" hint="请检查日期范围（一次最多 31 天）。" />
        ) : rows.length === 0 ? (
          <StateBlock title="没有冲突" hint="当前日期段内未发现冲突。" />
        ) : (
          <Table
            rowKey={(row) => `${row.lessonId}-${row.otherLessonId ?? ""}-${row.type}`}
            size="small"
            columns={columns}
            dataSource={rows}
            pagination={{ pageSize: 50 }}
          />
        )}
      </Panel>
    </Page>
  );
}
