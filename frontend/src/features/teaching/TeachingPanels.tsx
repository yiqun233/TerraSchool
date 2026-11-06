import {
  Alert,
  Button,
  Card,
  Input,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import type { TeacherWorkbench, TeacherWorkbenchClass } from "./teachingTypes";

/** 课次状态的中文文案；工作台主列表与教师课表面板共用。 */
export const LESSON_STATUS: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  CONFIRMED: "已确认",
  CLOSED: "已关闭",
};

/**
 * 教师本人课表与班级工作台。
 *
 * 纯展示：日期范围与查询结果都由主组件持有。工作台只读，不提交任何状态变更，
 * 点击班级时把选中的班级 id 交给主组件去打开抽屉。
 */
export function TeacherWorkbenchPanel({
  from,
  to,
  onFromChange,
  onToChange,
  data,
  error,
  fetching,
  onRefresh,
  onOpenClass,
}: {
  from: string;
  to: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  data: TeacherWorkbench | undefined;
  error: Error | null;
  fetching: boolean;
  onRefresh: () => void;
  onOpenClass: (classId: string) => void;
}) {
  return (
    <Card
      title="教师本人课表与班级工作台"
      size="small"
      style={{ marginBottom: 12 }}
    >
      <Space wrap style={{ marginBottom: 12 }}>
        <Input
          aria-label="教师课表开始日期"
          type="date"
          value={from}
          onChange={(event) => onFromChange(event.target.value)}
          style={{ width: 170 }}
        />
        <Input
          aria-label="教师课表结束日期"
          type="date"
          value={to}
          onChange={(event) => onToChange(event.target.value)}
          style={{ width: 170 }}
        />
        <Button onClick={() => void onRefresh()} loading={fetching}>
          刷新本人课表
        </Button>
      </Space>
      {error ? <Alert type="error" title={error.message} showIcon /> : null}
      <Table
        rowKey="id"
        size="small"
        loading={fetching}
        dataSource={data?.lessons ?? []}
        pagination={false}
        columns={[
          { title: "日期", dataIndex: "lessonDate" },
          {
            title: "时间",
            render: (
              _: unknown,
              row: NonNullable<TeacherWorkbench["lessons"]>[number],
            ) => `${row.startsAt}–${row.endsAt}`,
          },
          { title: "班级", dataIndex: "className" },
          { title: "课程", dataIndex: "curriculumName" },
          { title: "课题", dataIndex: "topic" },
          { title: "教室", dataIndex: "roomName" },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) => (
              <Tag>{LESSON_STATUS[status] ?? status}</Tag>
            ),
          },
        ]}
        locale={{
          emptyText: "该时间范围内暂无本人授课课次，或账号尚未绑定教师资料",
        }}
      />
      <Typography.Text type="secondary">
        授课班级：
        {(data?.classes ?? [])
          .map((item) => `${item.name}（${item.lessonCount} 次）`)
          .join("、") || "暂无"}
      </Typography.Text>
      <Table
        rowKey="id"
        size="small"
        style={{ marginTop: 8 }}
        dataSource={data?.classes ?? []}
        pagination={false}
        columns={[
          { title: "本人任教班级", dataIndex: "name" },
          { title: "课程", dataIndex: "curriculumName" },
          { title: "班级状态", dataIndex: "status" },
          {
            title: "工作台",
            render: (_: unknown, row: TeacherWorkbenchClass) => (
              <Button
                size="small"
                disabled={!row.id}
                onClick={() => {
                  if (row.id) onOpenClass(row.id);
                }}
              >
                查看班级工作台
              </Button>
            ),
          },
        ]}
        locale={{ emptyText: "暂无本人任教班级" }}
      />
    </Card>
  );
}
