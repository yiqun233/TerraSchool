import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Space,
  Table,
  Typography,
} from "antd";
import { LESSON_STATUS } from "./TeachingPanels";
import type {
  AttendanceReport,
  DailyResourceSchedule,
  TeachingOverviewReport,
} from "./teachingTypes";

/**
 * 教务报表面板：资源日程日报、综合情况、课次考勤统计。
 *
 * 三张卡片都是只读展示，日期范围与分页由主组件持有；放一起是因为它们共享
 * “日期范围 + 服务端汇总 + 空态文案”的同一套结构。
 */

/** 资源日程日报：某一天的课次、教师与教室分配情况。 */
export function ResourceDailyPanel({
  day,
  onDayChange,
  data,
  error,
  fetching,
  onRefresh,
  page,
  onPageChange,
}: {
  day: string;
  onDayChange: (value: string) => void;
  data: DailyResourceSchedule | undefined;
  error: Error | null;
  fetching: boolean;
  onRefresh: () => void;
  page: number;
  onPageChange: (page: number) => void;
}) {
  return (
    <Card title="资源日程日报" size="small" style={{ marginBottom: 12 }}>
      <Space wrap style={{ marginBottom: 12 }}>
        <Input
          aria-label="资源日程日期"
          type="date"
          value={day}
          onChange={(event) => onDayChange(event.target.value)}
          style={{ width: 170 }}
        />
        <Button onClick={onRefresh} loading={fetching}>
          刷新资源日程
        </Button>
        <Typography.Text type="secondary">
          未分配教师 {data?.missingTeacher ?? "—"}，未分配教室{" "}
          {data?.missingRoom ?? "—"}
        </Typography.Text>
      </Space>
      {error ? <Alert type="error" title={error.message} showIcon /> : null}
      <Table
        rowKey="lessonId"
        size="small"
        loading={fetching}
        dataSource={data?.lessons ?? []}
        pagination={{
          current: page,
          pageSize: 100,
          total: data?.total ?? 0,
          onChange: onPageChange,
          showSizeChanger: false,
        }}
        columns={[
          {
            title: "时间",
            render: (
              _: unknown,
              row: NonNullable<DailyResourceSchedule["lessons"]>[number],
            ) => `${row.startsAt}–${row.endsAt}`,
          },
          { title: "班级", dataIndex: "className" },
          {
            title: "教师",
            dataIndex: "teacherName",
            render: (name: string | null) => name ?? "未分配",
          },
          {
            title: "教室",
            dataIndex: "roomName",
            render: (name: string | null) => name ?? "未分配",
          },
          { title: "课题", dataIndex: "topic" },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) => LESSON_STATUS[status] ?? status,
          },
        ]}
        locale={{ emptyText: "该日没有已排课次" }}
      />
    </Card>
  );
}

/** 综合情况（候选口径）：班级状态快照、课次状态与考勤登记汇总。 */
export function TeachingOverviewPanel({
  data,
  error,
}: {
  data: TeachingOverviewReport | undefined;
  error: Error | null;
}) {
  return (
    <Card
      title="教务综合情况（候选口径）"
      size="small"
      style={{ marginBottom: 12 }}
    >
      <Space wrap style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">
          班级数为当前状态快照；课次与考勤按课次日期统计。候选数据待 Q62 确认。
        </Typography.Text>
      </Space>
      {error ? <Alert type="error" title={error.message} showIcon /> : null}
      {data ? (
        <>
          <Descriptions
            title="当前班级状态快照"
            column={3}
            size="small"
            bordered
          >
            <Descriptions.Item label="活动班级">
              {data.activeClasses}
            </Descriptions.Item>
            <Descriptions.Item label="已结班">
              {data.closedClasses}
            </Descriptions.Item>
            <Descriptions.Item label="已取消">
              {data.cancelledClasses}
            </Descriptions.Item>
          </Descriptions>
          <Descriptions
            title={`课次状态（${data.from} 至 ${data.to}）`}
            column={5}
            size="small"
            bordered
            style={{ marginTop: 12 }}
          >
            <Descriptions.Item label="课次总数">
              {data.lessons}
            </Descriptions.Item>
            <Descriptions.Item label="草稿">
              {data.draftLessons}
            </Descriptions.Item>
            <Descriptions.Item label="已发布">
              {data.publishedLessons}
            </Descriptions.Item>
            <Descriptions.Item label="已确认">
              {data.confirmedLessons}
            </Descriptions.Item>
            <Descriptions.Item label="已关闭">
              {data.closedLessons}
            </Descriptions.Item>
          </Descriptions>
          <Descriptions title="已登记考勤状态" column={5} size="small" bordered>
            <Descriptions.Item label="出勤">{data.present}</Descriptions.Item>
            <Descriptions.Item label="部分缺勤">
              {data.partial}
            </Descriptions.Item>
            <Descriptions.Item label="缺勤">{data.absent}</Descriptions.Item>
            <Descriptions.Item label="请假">{data.leave}</Descriptions.Item>
            <Descriptions.Item label="已登记">
              {data.recorded}
            </Descriptions.Item>
          </Descriptions>
        </>
      ) : null}
    </Card>
  );
}

/** 课次考勤统计：按课次汇总各考勤状态人数。 */
export function AttendanceReportPanel({
  from,
  to,
  onFromChange,
  onToChange,
  data,
  error,
  fetching,
}: {
  from: string;
  to: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  data: AttendanceReport | undefined;
  error: Error | null;
  fetching: boolean;
}) {
  return (
    <Card title="课次考勤统计" size="small" style={{ marginBottom: 12 }}>
      <Space wrap style={{ marginBottom: 12 }}>
        <Input
          aria-label="考勤统计开始日期"
          type="date"
          value={from}
          onChange={(event) => onFromChange(event.target.value)}
          style={{ width: 170 }}
        />
        <Input
          aria-label="考勤统计结束日期"
          type="date"
          value={to}
          onChange={(event) => onToChange(event.target.value)}
          style={{ width: 170 }}
        />
        <Typography.Text type="secondary">
          各状态仅统计已登记考勤；未登记课次显示为零。日期范围最多 31 天。
        </Typography.Text>
      </Space>
      {error ? <Alert type="error" title={error.message} showIcon /> : null}
      <Table
        rowKey="lessonId"
        size="small"
        loading={fetching}
        dataSource={data?.lessons ?? []}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        columns={[
          { title: "日期", dataIndex: "lessonDate" },
          {
            title: "时间",
            render: (
              _: unknown,
              row: NonNullable<AttendanceReport["lessons"]>[number],
            ) => `${row.startsAt}–${row.endsAt}`,
          },
          { title: "班级", dataIndex: "className" },
          {
            title: "课次状态",
            dataIndex: "lessonStatus",
            render: (status: string) => LESSON_STATUS[status] ?? status,
          },
          { title: "出勤", dataIndex: "present" },
          { title: "部分缺勤", dataIndex: "partial" },
          { title: "缺勤", dataIndex: "absent" },
          { title: "请假", dataIndex: "leave" },
          { title: "已登记", dataIndex: "recorded" },
        ]}
      />
    </Card>
  );
}
