import { useMutation, useQuery } from "@tanstack/react-query";
import {
  App as AntApp,
  Button,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type LessonView = components["schemas"]["LessonView"];
type ClassMemberView = components["schemas"]["ClassMemberView"];

type ClassOption = { id: string; name: string };
type Status = "PRESENT" | "ABSENT" | "LEAVE";

const CYCLE: Status[] = ["PRESENT", "ABSENT", "LEAVE"];
const STATUS_LABEL: Record<Status, string> = {
  PRESENT: "出勤",
  ABSENT: "缺勤",
  LEAVE: "请假",
};
const STATUS_COLOR: Record<Status, string> = {
  PRESENT: "green",
  ABSENT: "red",
  LEAVE: "orange",
};

const nextStatus = (current?: Status): Status | undefined => {
  if (!current) return "PRESENT";
  const index = CYCLE.indexOf(current);
  return index === CYCLE.length - 1 ? undefined : CYCLE[index + 1];
};

const cellKey = (lessonId: string, enrollmentId: string) => `${lessonId}|${enrollmentId}`;

/**
 * 批量录考勤：学员 × 本周课次 的网格，点格子循环“出勤 → 缺勤 → 请假 → 未录”。
 * 只保存已发布课次；保存只落考勤，不确认，确认仍在课次弹窗或批量操作里做。
 */
export function BatchAttendanceDialog({
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
  const [classId, setClassId] = useState<string>();
  const [marks, setMarks] = useState<Record<string, Status>>({});

  useEffect(() => {
    if (!open) return;
    setClassId((current) => current ?? classes[0]?.id);
  }, [open, classes]);

  const members = useQuery({
    queryKey: ["teaching", "members", classId],
    enabled: open && Boolean(classId),
    queryFn: async () => {
      const result = await api.GET("/api/teaching/classes/{id}/members", {
        params: { path: { id: classId as string } },
      });
      if (result.response.status === 401) {
        onUnauthorized();
        return [] as ClassMemberView[];
      }
      if (!result.data) throw new Error("学员加载失败");
      return result.data as ClassMemberView[];
    },
  });

  const lessons = useQuery({
    queryKey: ["teaching", "lessons", classId, weekStart],
    enabled: open && Boolean(classId),
    queryFn: async () => {
      const result = await api.GET("/api/teaching/lessons", {
        params: { query: { classId: classId as string } },
      });
      if (result.response.status === 401) {
        onUnauthorized();
        return [] as LessonView[];
      }
      if (!result.data) throw new Error("课次加载失败");
      return (result.data as LessonView[]).filter((item) => {
        const day = item.lessonDate ?? "";
        return day >= weekStart && day <= weekEnd;
      });
    },
  });

  const weekLessons = useMemo(
    () =>
      (lessons.data ?? [])
        .filter((item) => item.id)
        .slice()
        .sort((left, right) =>
          `${left.lessonDate ?? ""}${left.startsAt ?? ""}`.localeCompare(
            `${right.lessonDate ?? ""}${right.startsAt ?? ""}`,
          ),
        ),
    [lessons.data],
  );

  // 打开班级后把已有考勤读进来；没录过的格子保持未录。
  useEffect(() => {
    if (!open || !classId || !weekLessons.length) return;
    let cancelled = false;
    void (async () => {
      const next: Record<string, Status> = {};
      for (const lesson of weekLessons) {
        if (!lesson.id) continue;
        const result = await api.GET("/api/teaching/lessons/{id}/attendance", {
          params: { path: { id: lesson.id } },
        });
        if (result.response.status === 401) {
          onUnauthorized();
          return;
        }
        for (const record of result.data ?? []) {
          const status = record.status as Status | undefined;
          if (record.enrollmentId && status && CYCLE.includes(status)) {
            next[cellKey(lesson.id, record.enrollmentId)] = status;
          }
        }
      }
      if (!cancelled) setMarks(next);
    })();
    return () => {
      cancelled = true;
    };
  }, [open, classId, weekLessons, onUnauthorized]);

  const editable = weekLessons.filter((item) => item.status === "PUBLISHED");

  const save = useMutation({
    mutationFn: async () => {
      if (!classId) throw new Error("请先选择班级");
      let saved = 0;
      const failed: string[] = [];
      for (const lesson of editable) {
        if (!lesson.id) continue;
        const lessonId = lesson.id;
        const records = (members.data ?? [])
          .filter((member) => Boolean(member.enrollmentId))
          .map((member) => ({
            enrollmentId: member.enrollmentId as string,
            status: marks[cellKey(lessonId, member.enrollmentId as string)],
          }))
          .filter((item) => Boolean(item.status))
          .map((item) => ({ enrollmentId: item.enrollmentId, status: item.status as Status }));
        if (!records.length) continue;
        const result = await api.POST("/api/teaching/lessons/{id}/attendance-draft", {
          params: { path: { id: lesson.id } },
          body: { requestId: crypto.randomUUID(), records },
        });
        if (result.response.status === 401) {
          onUnauthorized();
          throw new Error("登录已失效");
        }
        if (!result.data) {
          failed.push(`${lesson.lessonDate ?? ""} ${(lesson.startsAt ?? "").slice(0, 5)}`);
        } else {
          saved += 1;
        }
      }
      return { saved, failed };
    },
    onSuccess: ({ saved, failed }) => {
      if (failed.length) message.warning(`已保存 ${saved} 节课，失败：${failed.join("、")}`);
      else message.success(`已保存 ${saved} 节课的考勤`);
      onDone();
      onClose();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const columns = [
    {
      title: "学员",
      dataIndex: "studentName",
      fixed: "left" as const,
      width: 130,
      render: (value: string | undefined) => value || "—",
    },
    ...weekLessons.map((lesson) => ({
      title: (
        <div style={{ lineHeight: 1.3 }}>
          <div>{(lesson.lessonDate ?? "").slice(5)}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {(lesson.startsAt ?? "").slice(0, 5)}–{(lesson.endsAt ?? "").slice(0, 5)}
          </Typography.Text>
        </div>
      ),
      key: lesson.id,
      width: 96,
      render: (_: unknown, member: ClassMemberView) => {
        const lessonId = lesson.id;
        const enrollmentId = member.enrollmentId;
        if (!lessonId || !enrollmentId) return "—";
        const status = marks[cellKey(lessonId, enrollmentId)];
        const locked = lesson.status !== "PUBLISHED";
        return (
          <Button
            size="small"
            type={status ? "default" : "dashed"}
            disabled={locked}
            title={locked ? "只有已发布课次能录考勤" : "点击切换状态"}
            onClick={() => {
              if (locked) return;
              const key = cellKey(lessonId, enrollmentId);
              const next = nextStatus(status);
              setMarks((current) => {
                const copy = { ...current };
                if (next) copy[key] = next;
                else delete copy[key];
                return copy;
              });
            }}
          >
            {status ? <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag> : "未录"}
          </Button>
        );
      },
    })),
  ];

  return (
    <Modal
      open={open}
      title="批量录考勤"
      width={Math.min(1180, 360 + weekLessons.length * 96)}
      onCancel={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>关闭</Button>
          <Button type="primary" loading={save.isPending} onClick={() => save.mutate()} disabled={!classId}>
            保存考勤
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
              setMarks({});
            }}
            options={classes.map((item) => ({ value: item.id, label: item.name }))}
          />
          <Typography.Text type="secondary">
            {weekStart} 至 {weekEnd}
          </Typography.Text>
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          点格子循环切换：出勤 → 缺勤 → 请假 → 未录。只保存已发布课次；保存只落考勤，不确认课次。
        </Typography.Paragraph>
        <Table<ClassMemberView>
          rowKey="enrollmentId"
          size="small"
          loading={members.isFetching || lessons.isFetching}
          dataSource={members.data ?? []}
          pagination={false}
          scroll={{ x: "max-content", y: 420 }}
          locale={{ emptyText: "该班没有在班学员，或本周没有课次" }}
          columns={columns}
        />
      </Space>
    </Modal>
  );
}
