import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Checkbox,
  Input,
  Modal,
  Select,
  Space,
  Typography,
} from "antd";
import { api } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type ClassRow = components["schemas"]["ClassView"];
type ClassCurriculum = components["schemas"]["ClassCurriculumView"];
type TeacherOption = components["schemas"]["TeacherOption"];
type RoomOption = components["schemas"]["RoomOption"];

const WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

function isoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

function failure(error: unknown, fallback: string) {
  if (error && typeof error === "object") {
    const value = error as { title?: string; code?: string; detail?: string };
    return value.detail || value.title || value.code || fallback;
  }
  return fallback;
}

/**
 * 批量添加课次：日期段 + 周几 + 时段，一次生成多节。
 *
 * 与旧系统一致：课程只能从本班已开设课程里选；教师/教室可留空，之后再补。
 * 命中停课日或该班已有课次的日期会被跳过，返回里能看到跳过了哪些。
 */
export function BatchLessonDialog({
  classes,
  onClose,
  onUnauthorized,
  onCreated,
}: {
  classes: ClassRow[];
  onClose: () => void;
  onUnauthorized: () => void;
  onCreated: () => void;
}) {
  const [classId, setClassId] = useState<string | undefined>(
    classes.find((item) => item.status === "ACTIVE")?.id ?? classes[0]?.id,
  );
  const [startText, setStartText] = useState(isoDate(new Date()));
  const [endText, setEndText] = useState(isoDate(addDays(new Date(), 13)));
  const [weekdays, setWeekdays] = useState<number[]>([1]);
  const [startsAt, setStartsAt] = useState("09:00");
  const [endsAt, setEndsAt] = useState("11:00");
  const [curriculumId, setCurriculumId] = useState<string>();
  const [teacherId, setTeacherId] = useState<string>();
  const [roomId, setRoomId] = useState<string>();
  const [topic, setTopic] = useState("");
  const [includeHolidays, setIncludeHolidays] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const [result, setResult] = useState<{ created: number; skipped: string[] }>();

  const curricula = useQuery({
    queryKey: ["timetable-class-curricula", classId],
    enabled: !!classId,
    queryFn: async ({ signal }): Promise<ClassCurriculum[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/curricula",
        { params: { path: { id: classId! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取班级课程");
      return data;
    },
  });

  const effectiveCurriculum =
    curriculumId ?? curricula.data?.find((item) => item.primary)?.curriculumId;

  const teachers = useQuery({
    queryKey: [
      "batch-teachers",
      classId,
      startText,
      startsAt,
      endsAt,
      effectiveCurriculum,
    ],
    enabled: !!classId && !!startText && !!startsAt && !!endsAt,
    queryFn: async ({ signal }): Promise<TeacherOption[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/teacher-options",
        {
          params: {
            path: { id: classId! },
            query: {
              day: startText,
              startsAt,
              endsAt,
              curriculumId: effectiveCurriculum,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取可选教师");
      return data;
    },
  });

  const rooms = useQuery({
    queryKey: ["batch-rooms", classId, startText, startsAt, endsAt],
    enabled: !!classId && !!startText && !!startsAt && !!endsAt,
    queryFn: async ({ signal }): Promise<RoomOption[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/room-options",
        {
          params: {
            path: { id: classId! },
            query: { day: startText, startsAt, endsAt },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取可选教室");
      return data;
    },
  });

  async function submit() {
    if (!classId) {
      setError("请先选择班级");
      return;
    }
    if (!weekdays.length) {
      setError("请至少选择一个星期几");
      return;
    }
    setSubmitting(true);
    setError(undefined);
    setResult(undefined);
    try {
      const { data, response, error: failed } = await api.POST(
        "/api/teaching/lessons/batch",
        {
          body: {
            requestId: crypto.randomUUID(),
            classId,
            startText,
            endText,
            weekdays,
            startsAt,
            endsAt,
            curriculumId: effectiveCurriculum,
            teacherId,
            roomId,
            topic: topic || undefined,
            includeHolidays,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) throw new Error(failure(failed, "批量添加失败"));
      setResult({ created: data.created ?? 0, skipped: data.skipped ?? [] });
      onCreated();
    } catch (problem) {
      setError((problem as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      open
      width={620}
      title="添加课次"
      onCancel={onClose}
      footer={[
        <Button key="close" onClick={onClose}>
          关闭
        </Button>,
        <Button
          key="submit"
          type="primary"
          loading={submitting}
          onClick={() => void submit()}
        >
          生成课次
        </Button>,
      ]}
    >
      {error ? (
        <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />
      ) : null}
      {result ? (
        <Alert
          type={result.created > 0 ? "success" : "warning"}
          showIcon
          message={`已生成 ${result.created} 节课次`}
          description={
            result.skipped.length ? (
              <div>
                跳过 {result.skipped.length} 个日期：
                <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
                  {result.skipped.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </div>
            ) : (
              "没有跳过任何日期"
            )
          }
          style={{ marginBottom: 12 }}
        />
      ) : null}
      <Space direction="vertical" style={{ width: "100%" }} size={12}>
        <div>
          <Typography.Text type="secondary">班级</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={classId}
            onChange={(value) => {
              setClassId(value);
              setCurriculumId(undefined);
            }}
            options={classes.map((item) => ({
              value: item.id!,
              label: `${item.name}${item.curriculumName ? ` · ${item.curriculumName}` : ""}`,
            }))}
          />
        </div>
        <div>
          <Typography.Text type="secondary">日期段（最长 90 天）</Typography.Text>
          <Space.Compact style={{ width: "100%" }}>
            <Input
              type="date"
              aria-label="开始日期"
              value={startText}
              onChange={(event) => setStartText(event.target.value)}
            />
            <Input
              type="date"
              aria-label="结束日期"
              value={endText}
              onChange={(event) => setEndText(event.target.value)}
            />
          </Space.Compact>
        </div>
        <div>
          <Typography.Text type="secondary">星期几</Typography.Text>
          <div style={{ marginTop: 4 }}>
            <Checkbox.Group
              value={weekdays}
              onChange={(values) => setWeekdays(values as number[])}
              options={WEEKDAYS.map((label, index) => ({
                label,
                value: index + 1,
              }))}
            />
          </div>
        </div>
        <div>
          <Typography.Text type="secondary">时段</Typography.Text>
          <Space.Compact style={{ width: "100%" }}>
            <Input
              type="time"
              aria-label="开始时间"
              value={startsAt}
              onChange={(event) => setStartsAt(event.target.value)}
            />
            <Input
              type="time"
              aria-label="结束时间"
              value={endsAt}
              onChange={(event) => setEndsAt(event.target.value)}
            />
          </Space.Compact>
        </div>
        <div>
          <Typography.Text type="secondary">课程</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={effectiveCurriculum}
            onChange={setCurriculumId}
            placeholder="选择本班开设的课程"
            options={(curricula.data ?? []).map((item) => ({
              value: item.curriculumId!,
              label: `${item.curriculumName}${item.primary ? "（主课程）" : ""} · ${item.hours ?? 0} 课时`,
            }))}
          />
        </div>
        <div>
          <Typography.Text type="secondary">教师（可留空，之后再补）</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={teacherId}
            onChange={setTeacherId}
            allowClear
            placeholder="选择教师"
            options={(teachers.data ?? []).map((item) => ({
              value: item.id!,
              disabled: !item.available,
              label: `${item.name}${item.available ? "" : `（${item.reason}）`}`,
            }))}
          />
        </div>
        <div>
          <Typography.Text type="secondary">教室（可留空，之后再补）</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={roomId}
            onChange={setRoomId}
            allowClear
            placeholder="选择教室"
            options={(rooms.data ?? []).map((item) => ({
              value: item.id!,
              disabled: !item.available,
              label: `${item.name}${item.campusName ? ` · ${item.campusName}` : ""}${
                item.available ? "" : `（${item.reason}）`
              }`,
            }))}
          />
        </div>
        <div>
          <Typography.Text type="secondary">课题（可留空）</Typography.Text>
          <Input value={topic} onChange={(event) => setTopic(event.target.value)} />
        </div>
        <Checkbox
          checked={includeHolidays}
          onChange={(event) => setIncludeHolidays(event.target.checked)}
        >
          包含学校停课日
        </Checkbox>
      </Space>
    </Modal>
  );
}
