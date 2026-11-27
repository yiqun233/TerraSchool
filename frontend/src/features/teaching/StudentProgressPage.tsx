import { useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { useQuery } from "@tanstack/react-query";
import { Button, Input, Select, Table, Typography } from "antd";
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

type StudentProgress = components["schemas"]["StudentProgressView"];

function number(value: string | undefined) {
  return Number(value ?? 0);
}

/** 学员进度：按报名 × 课程看已消费/剩余课时与课节进度，点学员可继续到报名明细。 */
export function StudentProgressPage({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");

  const capabilities = useQuery({
    queryKey: ["teaching-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/capabilities", {
        signal,
      });
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

  const progress = useQuery({
    queryKey: ["student-progress", effectiveSchool, search],
    enabled: Boolean(effectiveSchool),
    queryFn: async ({ signal }): Promise<StudentProgress[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/student-progress",
        {
          params: {
            query: {
              schoolId: effectiveSchool!,
              keyword: search === "" ? undefined : search,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取学员进度");
      return data;
    },
  });

  if (capabilities.isPending)
    return (
      <Page>
        <PageHeader title="学员进度" description="正在确认教务权限…" />
        <LoadingBlock>正在确认教务权限…</LoadingBlock>
      </Page>
    );
  if (capabilities.error)
    return (
      <Page>
        <PageHeader title="学员进度" description="按报名 × 课程查看课时与课节进度。" />
        <Panel>
          <StateBlock
            title="无法读取教务权限"
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
        <PageHeader title="学员进度" description="按报名 × 课程查看课时与课节进度。" />
        <Panel>
          <StateBlock
            title="当前范围内没有学校"
            hint="请先建校并给账号分配学校范围，学员进度才有数据。"
          />
        </Panel>
      </Page>
    );

  const rows = progress.data ?? [];
  const totals = rows.reduce(
    (acc, row) => ({
      consumed: acc.consumed + number(row.consumedHours),
      remaining: acc.remaining + number(row.remainingHours),
    }),
    { consumed: 0, remaining: 0 },
  );

  const columns: ColumnsType<StudentProgress> = [
    {
      title: "学员",
      dataIndex: "studentName",
      render: (_: unknown, row) => (
        <SpaceText primary={row.studentName} secondary={row.studentNo} />
      ),
    },
    {
      title: "报名 / 课程",
      dataIndex: "productName",
      render: (_: unknown, row) => (
        <SpaceText primary={row.productName} secondary={row.curriculumName} />
      ),
    },
    {
      title: "课时（已消费 / 总）",
      dataIndex: "consumedHours",
      render: (_: unknown, row) => `${row.consumedHours} / ${row.totalHours}`,
    },
    {
      title: "剩余课时",
      dataIndex: "remainingHours",
      render: (value: string) => (
        <Typography.Text type={number(value) <= 2 ? "danger" : undefined}>
          {value}
        </Typography.Text>
      ),
    },
    {
      title: "课节（已上 / 排定）",
      dataIndex: "attendedLessons",
      render: (_: unknown, row) =>
        `${row.attendedLessons} / ${row.totalLessons}`,
    },
    {
      title: "下一节",
      dataIndex: "nextLessonDate",
      render: (_: unknown, row) =>
        row.nextLessonDate
          ? `第 ${row.nextLessonIndex} 节 · ${row.nextLessonDate} ${row.nextStartsAt ?? ""}`.trim()
          : "—",
    },
  ];

  return (
    <Page>
      <PageHeader
        title="学员进度"
        description="按报名与课程看已消费课时、剩余课时和课节进度；剩余 <= 2 课时标红。"
        actions={
          <Button
            onClick={() => void progress.refetch()}
            loading={progress.isFetching}
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
        <Input.Search
          aria-label="学员姓名或学号"
          style={{ width: 260 }}
          placeholder="按姓名或学号筛选"
          value={keyword}
          allowClear
          onChange={(event) => setKeyword(event.target.value)}
          onSearch={(value) => setSearch(value.trim())}
        />
        <Typography.Text type="secondary">
          只统计已生效报名；课节按已发布/已确认/已关闭的课次
        </Typography.Text>
      </Toolbar>
      <StatStrip
        items={[
          { label: "报名课程数", value: rows.length },
          { label: "已消费课时", value: totals.consumed.toFixed(2) },
          { label: "剩余课时", value: totals.remaining.toFixed(2) },
        ]}
      />
      <Panel ariaLabel="学员进度">
        {progress.error ? (
          <StateBlock
            title="学员进度加载失败"
            hint={progress.error.message}
            action={
              <Button onClick={() => void progress.refetch()}>重试</Button>
            }
          />
        ) : (
          <Table
            rowKey={(row) => `${row.enrollmentId}:${row.curriculumId}`}
            loading={progress.isPending}
            dataSource={rows}
            columns={columns}
            locale={{
              emptyText: search
                ? `没有匹配“${search}”的学员`
                : "该校还没有生效报名",
            }}
            pagination={false}
            size="middle"
          />
        )}
      </Panel>
    </Page>
  );
}

function SpaceText({
  primary,
  secondary,
}: {
  primary?: string | null;
  secondary?: string | null;
}) {
  return (
    <div>
      <div>{primary ?? "—"}</div>
      {secondary ? (
        <Typography.Text type="secondary">{secondary}</Typography.Text>
      ) : null}
    </div>
  );
}
