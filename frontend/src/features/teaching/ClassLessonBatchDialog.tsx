import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { App as AntApp, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { api } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type LessonView = components["schemas"]["LessonView"];

const STATUS_LABEL: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  CONFIRMED: "已确认",
  CLOSED: "已关闭",
};

const STATUS_COLOR: Record<string, string> = {
  DRAFT: "default",
  PUBLISHED: "blue",
  CONFIRMED: "green",
  CLOSED: "default",
};

const SKIP_LABEL: Record<string, string> = {
  LESSON_NOT_DRAFT: "不是草稿",
  LESSON_NOT_PUBLISHED: "不是已发布",
  LESSON_NOT_CONFIRMED: "不是已确认",
  LESSON_NOT_RESCHEDULABLE: "当前状态不可改时间",
  ATTENDANCE_NOT_RECORDED: "没有在班学员",
  ATTENDANCE_MUST_COVER_MEMBERS: "考勤未覆盖全部在班学员",
  TEACHER_CLASH: "教师已有课",
  ROOM_CLASH: "教室被占用",
  TEACHER_MIN_GAP: "教师课间间隔不足",
  TEACHER_UNAVAILABLE: "教师不可用",
  ROOM_UNAVAILABLE: "教室不可用",
  LESSON_IN_PAST: "日期已过去",
  LESSON_TIME_OUT_OF_RANGE: "平移后超出当天时间",
  VERSION_CONFLICT: "版本已变化，请刷新",
};

const skipText = (code?: string) => (code ? (SKIP_LABEL[code] ?? code) : "未知原因");

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

type ClassOption = { id: string; name: string };

/**
 * 班级课次批量操作：发布、按考勤确认、关课，以及整班平移时段、复制到下周。
 * 服务端逐个课次走完整校验，失败只跳过该条并回传原因码。
 */
export function ClassLessonBatchDialog({
  open,
  classes,
  weekStart,
  weekEnd,
  onClose,
  onDone,
  onUnauthorized,
}: {
  open: boolean;
  classes: ClassOption[];
  weekStart: string;
  weekEnd: string;
  onClose: () => void;
  onDone: () => void;
  onUnauthorized: () => void;
}) {
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const [classId, setClassId] = useState<string>();
  const [selected, setSelected] = useState<string[]>([]);
  const [shiftMinutes, setShiftMinutes] = useState<number>(30);
  const [shiftFrom, setShiftFrom] = useState<string>(weekStart);
  const [shiftTo, setShiftTo] = useState<string>(weekEnd);
  const [copyWeek, setCopyWeek] = useState<string>(weekStart);

  useEffect(() => {
    if (!open) {
      setSelected([]);
      return;
    }
    setClassId((current) => current ?? classes[0]?.id);
    setShiftFrom(weekStart);
    setShiftTo(weekEnd);
    setCopyWeek(weekStart);
  }, [open, classes, weekStart, weekEnd]);

  const lessons = useQuery({
    queryKey: ["teaching", "lessons", classId],
    enabled: open && Boolean(classId),
    queryFn: async () => {
      const result = await api.GET("/api/teaching/lessons", {
        params: { query: { classId: classId as string } },
      });
      if (result.response.status === 401) {
        onUnauthorized();
        return [];
      }
      if (!result.data) throw new Error("课次加载失败");
      return result.data as LessonView[];
    },
  });

  const rows = (lessons.data ?? []).filter((item) => {
    const day = item.lessonDate ?? "";
    return day >= weekStart && day <= weekEnd;
  });

  const refresh = () => {
    setSelected([]);
    void queryClient.invalidateQueries({ queryKey: ["teaching"] });
    void lessons.refetch();
    onDone();
  };

  const run = useMutation({
    mutationFn: async (kind: "publish" | "confirm" | "close") => {
      const path =
        kind === "publish"
          ? "/api/teaching/lessons/publish-batch"
          : kind === "confirm"
            ? "/api/teaching/lessons/confirm-batch"
            : "/api/teaching/lessons/close-batch";
      const result = await api.POST(path, {
        body: { requestId: crypto.randomUUID(), lessonIds: selected },
      });
      if (result.response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!result.data) throw new Error("批量操作失败");
      return result.data;
    },
    onSuccess: (data, kind) => {
      const title = kind === "publish" ? "发布" : kind === "confirm" ? "确认" : "关课";
      const skipped = data.skipped ?? [];
      if (skipped.length) {
        const codes = [...new Set(skipped.map((item) => skipText(item.code)))];
        message.warning(`${title}完成 ${data.done} 个，跳过 ${skipped.length} 个（${codes.join("、")}）`);
      } else {
        message.success(`${title}完成 ${data.done} 个`);
      }
      refresh();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const shift = useMutation({
    mutationFn: async () => {
      if (!classId) throw new Error("请先选择班级");
      if (!DATE_RE.test(shiftFrom) || !DATE_RE.test(shiftTo))
        throw new Error("日期格式应为 YYYY-MM-DD");
      const result = await api.POST("/api/teaching/classes/{id}/lessons/shift", {
        params: { path: { id: classId } },
        body: { requestId: crypto.randomUUID(), fromDate: shiftFrom, toDate: shiftTo, minutes: shiftMinutes },
      });
      if (result.response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!result.data) throw new Error("平移失败");
      return result.data;
    },
    onSuccess: (data) => {
      const skipped = data.skipped ?? [];
      if (skipped.length) {
        const codes = [...new Set(skipped.map((item) => skipText(item.code)))];
        message.warning(`平移完成 ${data.done} 个，跳过 ${skipped.length} 个（${codes.join("、")}）`);
      } else {
        message.success(`已平移 ${data.done} 个课次`);
      }
      refresh();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const copy = useMutation({
    mutationFn: async () => {
      if (!classId) throw new Error("请先选择班级");
      if (!DATE_RE.test(copyWeek)) throw new Error("日期格式应为 YYYY-MM-DD");
      const result = await api.POST("/api/teaching/classes/{id}/lessons/copy-week", {
        params: { path: { id: classId } },
        body: { requestId: crypto.randomUUID(), sourceWeekStart: copyWeek },
      });
      if (result.response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!result.data) throw new Error("复制失败");
      return result.data;
    },
    onSuccess: (data) => {
      const skipped = (data.skipped ?? []).length;
      if (skipped) message.warning(`已复制 ${data.created} 节，跳过 ${skipped} 节（${(data.skipped ?? []).join("；")}）`);
      else message.success(`已复制 ${data.created} 节课到下周（草稿）`);
      refresh();
    },
    onError: (error: Error) => message.error(error.message),
  });

  return (
    <Modal
      open={open}
      title="班级课次批量操作"
      width={980}
      onCancel={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>关闭</Button>
          <Button
            onClick={() => run.mutate("publish")}
            loading={run.isPending && run.variables === "publish"}
            disabled={!selected.length}
          >
            批量发布
          </Button>
          <Button
            onClick={() => run.mutate("confirm")}
            loading={run.isPending && run.variables === "confirm"}
            disabled={!selected.length}
          >
            确认上课（全勤）
          </Button>
          <Button
            danger
            onClick={() => run.mutate("close")}
            loading={run.isPending && run.variables === "close"}
            disabled={!selected.length}
          >
            批量关课
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" size={12} style={{ width: "100%" }}>
        <Space wrap>
          <Typography.Text>班级</Typography.Text>
          <Select
            style={{ width: 260 }}
            value={classId}
            onChange={(value) => {
              setClassId(value);
              setSelected([]);
            }}
            options={classes.map((item) => ({ value: item.id, label: item.name }))}
          />
          <Typography.Text type="secondary">
            {weekStart} 至 {weekEnd}
          </Typography.Text>
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          发布只处理草稿；确认把已发布课次按全勤提交；已录过考勤的按已录结果；关课只处理已确认课次，关课即固化课时消耗、不可撤销。
        </Typography.Paragraph>
        <Table<LessonView>
          rowKey="id"
          size="small"
          loading={lessons.isFetching}
          dataSource={rows}
          pagination={false}
          scroll={{ y: 260 }}
          rowSelection={{
            selectedRowKeys: selected,
            onChange: (keys) => setSelected(keys.map(String)),
          }}
          locale={{ emptyText: "本周该班没有课次" }}
          columns={[
            { title: "日期", dataIndex: "lessonDate", width: 110 },
            {
              title: "时间",
              width: 130,
              render: (_, row) =>
                `${(row.startsAt ?? "").slice(0, 5)}–${(row.endsAt ?? "").slice(0, 5)}`,
            },
            { title: "课题", dataIndex: "topic", ellipsis: true },
            {
              title: "教师",
              dataIndex: "teacherName",
              width: 100,
              render: (value) => value || <Typography.Text type="danger">未安排</Typography.Text>,
            },
            {
              title: "状态",
              dataIndex: "status",
              width: 90,
              render: (value: string) => (
                <Tag color={STATUS_COLOR[value]}>{STATUS_LABEL[value] ?? value}</Tag>
              ),
            },
            {
              title: "考勤",
              dataIndex: "attendanceCount",
              width: 70,
              render: (value: number) => value || "—",
            },
          ]}
        />
        <Space wrap align="center">
          <Typography.Text strong>整班平移</Typography.Text>
          <Input
            value={shiftFrom}
            onChange={(event) => setShiftFrom(event.target.value)}
            style={{ width: 130 }}
            placeholder="起始日期"
          />
          <Input
            value={shiftTo}
            onChange={(event) => setShiftTo(event.target.value)}
            style={{ width: 130 }}
            placeholder="结束日期"
          />
          <InputNumber
            value={shiftMinutes}
            onChange={(value) => setShiftMinutes(Number(value ?? 0))}
            min={-720}
            max={720}
            step={5}
            addonAfter="分钟"
            style={{ width: 150 }}
          />
          <Button onClick={() => shift.mutate()} loading={shift.isPending} disabled={!classId || shiftMinutes === 0}>
            平移时段
          </Button>
          <Typography.Text type="secondary">正数往后移，负数往前移；只动草稿与已发布课次。</Typography.Text>
        </Space>
        <Space wrap align="center">
          <Typography.Text strong>复制到下周</Typography.Text>
          <Input
            value={copyWeek}
            onChange={(event) => setCopyWeek(event.target.value)}
            style={{ width: 130 }}
            placeholder="该周周一日期"
          />
          <Button onClick={() => copy.mutate()} loading={copy.isPending} disabled={!classId}>
            复制该周课表到下一周
          </Button>
          <Typography.Text type="secondary">新生成的课次是草稿，冲突或超出班级起止日期会跳过。</Typography.Text>
        </Space>
      </Space>
    </Modal>
  );
}
