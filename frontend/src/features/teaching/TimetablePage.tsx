import { useEffect, useMemo, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { useQueries, useQuery } from "@tanstack/react-query";
import {
  Alert,
  App as AntApp,
  Badge,
  Button,
  Checkbox,
  Drawer,
  Input,
  List,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
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
import { LESSON_STATUS } from "./TeachingPanels";
import { BatchAttendanceDialog } from "./BatchAttendanceDialog";
import { ClassLessonBatchDialog } from "./ClassLessonBatchDialog";
import { BatchLessonDialog } from "./BatchLessonDialog";
import type { DailyResourceSchedule } from "./teachingTypes";

type Slot = NonNullable<DailyResourceSchedule["lessons"]>[number];
type ClassRow = components["schemas"]["ClassView"];
type MinuteSpace = components["schemas"]["MinuteSpace"];
type LessonRow = components["schemas"]["LessonView"];
type ClassCurriculum = components["schemas"]["ClassCurriculumView"];
type TeacherOption = components["schemas"]["TeacherOption"];
type RoomOption = components["schemas"]["RoomOption"];
type LessonConflict = components["schemas"]["LessonConflict"];
type SwapSchemePlan = components["schemas"]["SwapSchemePlan"];
type MoveScheme = components["schemas"]["MoveScheme"];
type TimetableGroupRow = components["schemas"]["TimetableGroupRow"];
type TimetableCellView = components["schemas"]["TimetableCellView"];

/** 拖拽可行性底色：绿=可放、红=占用/冲突、灰=不可用。 */
const CELL_BG: Record<string, string> = {
  FREE: "#f6ffed",
  CLASS_OCCUPIED: "#fff1f0",
  STUDENT_CLASH: "#fff1f0",
  TEACHER_CLASH: "#fff1f0",
  ROOM_CLASH: "#fff1f0",
  OTHER: "#f5f5f5",
};

const WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

/** 学校还没配时段模板时的兜底，与演示种子的三段一致。 */
const FALLBACK_SPACES: MinuteSpace[] = [
  { beginMinute: 540, endMinute: 720 },
  { beginMinute: 840, endMinute: 1050 },
  { beginMinute: 1080, endMinute: 1260 },
];

/** 按本地时区取日期，避免 toISOString 把晚上 8 点后的日期推到第二天。 */
function isoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

/** 周视图以周一为起点；周日算上一周的周日，符合排课习惯。 */
function weekStart(offsetWeeks: number) {
  const today = new Date();
  today.setHours(12, 0, 0, 0);
  return weekStartOf(today, offsetWeeks);
}

/** 任意日期所在周的周一（周日算上一周），月视图复用它计算日历首格。 */
function weekStartOf(date: Date, offsetWeeks = 0) {
  const day = date.getDay();
  return addDays(date, (day === 0 ? -6 : 1 - day) + offsetWeeks * 7);
}

function minutesOf(value?: string) {
  if (!value) return Number.NaN;
  const [hour, minute] = value.split(":").map(Number);
  return hour * 60 + minute;
}

function clock(minute?: number) {
  if (typeof minute !== "number") return "";
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

/** 按分钟平移 HH:mm，跨天返回 undefined（调用方提示手动调整）。 */
function shiftClock(value: string | undefined, delta: number) {
  if (!value || !/^\d{2}:\d{2}$/.test(value)) return undefined;
  const [hour, minute] = value.split(":").map(Number);
  const total = hour * 60 + minute + delta;
  if (total < 0 || total >= 24 * 60) return undefined;
  return clock(total);
}

const STATUS_COLOR: Record<string, string> = {
  DRAFT: "#d9d9d9",
  PUBLISHED: "#1677ff",
  CONFIRMED: "#52c41a",
  CLOSED: "#8c8c8c",
};

/** 顶栏通知：站内信首版学校级，调课/改派成功后自动落一条。 */
function NotificationBell({ schoolId }: { schoolId?: string }) {
  const [open, setOpen] = useState(false);
  const list = useQuery({
    queryKey: ["teaching-notifications", schoolId],
    enabled: Boolean(schoolId),
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/notifications", {
        params: { query: { schoolId: schoolId!, unreadOnly: false } },
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取通知");
      return data;
    },
  });
  const rows = list.data ?? [];
  const unread = rows.filter((row) => !row.read).length;
  return (
    <>
      <Badge count={unread} size="small">
        <Button onClick={() => setOpen(true)}>通知</Button>
      </Badge>
      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        title="调课通知"
        width={420}
        destroyOnHidden
      >
        {rows.length === 0 ? (
          <Typography.Text type="secondary">暂无通知</Typography.Text>
        ) : (
          <List
            dataSource={rows}
            renderItem={(item) => (
              <List.Item
                style={{ cursor: item.read ? "default" : "pointer" }}
                onClick={async () => {
                  if (item.read || !item.id) return;
                  await api.POST("/api/teaching/notifications/{id}/read", {
                    params: { path: { id: item.id } },
                  });
                  void list.refetch();
                }}
              >
                <List.Item.Meta
                  title={
                    <Space>
                      <Typography.Text strong={!item.read}>{item.title}</Typography.Text>
                      {!item.read && <Tag color="red">未读</Tag>}
                    </Space>
                  }
                  description={
                    <>
                      {item.body}
                      <br />
                      <Typography.Text type="secondary">{item.createdAt}</Typography.Text>
                    </>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Drawer>
    </>
  );
}

/** 教师模式批量改派：把某教师一周内的课次整体交给另一位教师。 */
function TeacherReassignBar({
  schoolId,
  from,
  to,
  onDone,
}: {
  schoolId?: string;
  from: string;
  to: string;
  onDone: () => void;
}) {
  const { message } = AntApp.useApp();
  const [source, setSource] = useState<string>();
  const [target, setTarget] = useState<string>();
  const [busy, setBusy] = useState(false);
  const teachers = useQuery({
    queryKey: ["teaching-teachers", schoolId],
    enabled: Boolean(schoolId),
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/schools/{schoolId}/teachers", {
        params: { path: { schoolId: schoolId! } },
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取教师");
      return data;
    },
  });
  const options = (teachers.data ?? []).map((item) => ({
    value: item.id!,
    label: item.name,
  }));
  const run = async () => {
    if (!source || !target || !schoolId) {
      message.warning("请选择原教师与新教师");
      return;
    }
    setBusy(true);
    try {
      const { data, response } = await api.POST(
        "/api/teaching/teachers/{teacherId}/lessons/reassign",
        {
          params: { path: { teacherId: source } },
          body: { requestId: crypto.randomUUID(), from, to, newTeacherId: target },
        },
      );
      if (!response.ok || !data) throw new Error("改派失败");
      message.success(
        `已改派 ${data.done ?? 0} 节课${(data.skipped ?? []).length ? `，跳过 ${data.skipped!.length} 节` : ""}`,
      );
      onDone();
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Space wrap style={{ marginBottom: 8 }}>
      <Typography.Text type="secondary">批量改派：</Typography.Text>
      <Select
        style={{ width: 160 }}
        placeholder="原教师"
        options={options}
        value={source}
        onChange={setSource}
        showSearch
        optionFilterProp="label"
      />
      <Typography.Text type="secondary">→</Typography.Text>
      <Select
        style={{ width: 160 }}
        placeholder="新教师"
        options={options}
        value={target}
        onChange={setTarget}
        showSearch
        optionFilterProp="label"
      />
      <Button loading={busy} onClick={() => void run()}>
        改派本周课次
      </Button>
    </Space>
  );
}

function SlotChip({
  slot,
  conflicts,
  dragging,
  onOpen,
  onDragStart,
  onDragEnd,
}: {
  slot: Slot;
  conflicts?: LessonConflict[];
  dragging?: boolean;
  onOpen: (slot: Slot) => void;
  onDragStart?: (slot: Slot) => void;
  onDragEnd?: () => void;
}) {
  const missingTeacher = !slot.teacherName;
  const missingRoom = !slot.roomName;
  const clashing = (conflicts?.length ?? 0) > 0;
  return (
    <button
      type="button"
      draggable={Boolean(onDragStart)}
      onDragStart={(event) => {
        if (!onDragStart) return;
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData("text/plain", slot.lessonId ?? "");
        onDragStart(slot);
      }}
      onDragEnd={() => onDragEnd?.()}
      onClick={() => onOpen(slot)}
      style={{
        display: "block",
        width: "100%",
        textAlign: "left",
        cursor: onDragStart ? "grab" : "pointer",
        opacity: dragging ? 0.35 : 1,
        border: clashing ? "1px solid #cf1322" : "none",
        borderLeft: `3px solid ${
          clashing ? "#cf1322" : (STATUS_COLOR[slot.status ?? ""] ?? "#d9d9d9")
        }`,
        background: clashing ? "#fff1f0" : "#fafafa",
        borderRadius: 4,
        padding: "4px 6px",
        marginBottom: 4,
        fontSize: 12,
        lineHeight: 1.5,
      }}
      title={clashing ? conflicts!.map((item) => item.message).join("；") : undefined}
    >
      <div style={{ fontWeight: 600 }}>
        {slot.startsAt}–{slot.endsAt}
        <span style={{ fontWeight: 400, color: "#8c8c8c", marginLeft: 6 }}>
          {LESSON_STATUS[slot.status ?? ""] ?? slot.status}
        </span>
        {clashing ? (
          <span style={{ color: "#cf1322", marginLeft: 6 }}>
            {conflicts!.length} 处冲突
          </span>
        ) : null}
      </div>
      <div>{slot.topic || "未填课题"}</div>
      <div style={{ color: missingTeacher ? "#cf1322" : "#595959" }}>
        {slot.teacherName || "未排教师"}
        {missingRoom ? (
          <span style={{ color: "#cf1322" }}> · 未排教室</span>
        ) : (
          <span> · {slot.roomName}</span>
        )}
      </div>
    </button>
  );
}

function failure(error: unknown, fallback: string) {
  if (error && typeof error === "object") {
    const value = error as { title?: string; code?: string; detail?: string };
    return value.detail || value.title || value.code || fallback;
  }
  return fallback;
}

/**
 * 课次资源设置：课程取自本班已开设课程，教师/教室候选由服务端按时段算出。
 *
 * 与旧系统一致：教师候选只列本班课程的可授教师；已确认/已关闭课次不允许再改。
 */
function LessonResourceDialog({
  slot,
  classId,
  onClose,
  onUnauthorized,
  onSaved,
}: {
  slot: Slot;
  classId: string;
  onClose: () => void;
  onUnauthorized: () => void;
  onSaved: () => void;
}) {
  const [curriculumId, setCurriculumId] = useState<string>();
  const [teacherId, setTeacherId] = useState<string>();
  const [roomId, setRoomId] = useState<string>();
  const [actualTeacherId, setActualTeacherId] = useState<string>();
  const [actualRoomId, setActualRoomId] = useState<string>();
  const [editDate, setEditDate] = useState<string>();
  const [editStartsAt, setEditStartsAt] = useState<string>();
  const [editEndsAt, setEditEndsAt] = useState<string>();
  const [memo, setMemo] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string>();
  const { message } = AntApp.useApp();
  const [schemeOpen, setSchemeOpen] = useState(false);
  const [schemeLoading, setSchemeLoading] = useState(false);
  const [schemes, setSchemes] = useState<SwapSchemePlan[]>([]);
  const [schemeIndex, setSchemeIndex] = useState(0);
  const [schemeReason, setSchemeReason] = useState("链式调课");
  const [schemeExecuting, setSchemeExecuting] = useState(false);
  const [schemeError, setSchemeError] = useState<string>();

  const lessons = useQuery({
    queryKey: ["timetable-class-lessons", classId],
    queryFn: async ({ signal }): Promise<LessonRow[]> => {
      const { data, response } = await api.GET("/api/teaching/lessons", {
        params: { query: { classId } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取该班课次");
      return data;
    },
  });

  const lesson = lessons.data?.find((item) => item.id === slot.lessonId);

  const curricula = useQuery({
    queryKey: ["timetable-class-curricula", classId],
    queryFn: async ({ signal }): Promise<ClassCurriculum[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/curricula",
        { params: { path: { id: classId } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取班级课程");
      return data;
    },
  });

  const primaryCurriculum = curricula.data?.find(
    (item) => item.primary,
  )?.curriculumId;
  const effectiveCurriculum = curriculumId ?? primaryCurriculum;

  const teachers = useQuery({
    queryKey: [
      "timetable-teachers",
      classId,
      slot.lessonDate,
      slot.startsAt,
      slot.endsAt,
      effectiveCurriculum,
    ],
    enabled: !!lesson,
    queryFn: async ({ signal }): Promise<TeacherOption[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/teacher-options",
        {
          params: {
            path: { id: classId },
            query: {
              day: slot.lessonDate!,
              startsAt: slot.startsAt!,
              endsAt: slot.endsAt!,
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
    queryKey: ["timetable-rooms", classId, slot.lessonDate, slot.startsAt, slot.endsAt],
    enabled: !!lesson,
    queryFn: async ({ signal }): Promise<RoomOption[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/room-options",
        {
          params: {
            path: { id: classId },
            query: {
              day: slot.lessonDate!,
              startsAt: slot.startsAt!,
              endsAt: slot.endsAt!,
            },
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

  const locked = lesson?.status === "CONFIRMED" || lesson?.status === "CLOSED";

  const conflicts = useQuery({
    queryKey: ["lesson-conflicts", lesson?.id],
    enabled: !!lesson,
    queryFn: async ({ signal }): Promise<LessonConflict[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/lessons/{id}/conflicts",
        { params: { path: { id: lesson!.id! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取冲突明细");
      return data;
    },
  });

  useEffect(() => {
    if (!lesson) return;
    setEditDate(lesson.lessonDate ?? undefined);
    setEditStartsAt(lesson.startsAt ?? undefined);
    setEditEndsAt(lesson.endsAt ?? undefined);
    setActualTeacherId(lesson.actualTeacherId ?? lesson.teacherId ?? undefined);
    setActualRoomId(lesson.actualRoomId ?? lesson.roomId ?? undefined);
    setMemo(lesson.memo ?? "");
  }, [lesson]);

  async function save(publish: boolean) {
    if (!lesson) return;
    setSaving(true);
    setError(undefined);
    try {
      if (publish) {
        const { response, error: failed } = await api.POST(
          "/api/teaching/lessons/{id}/publish",
          {
            params: { path: { id: lesson.id! } },
            body: {
              expectedVersion: lesson.version,
            },
          },
        );
        if (response.status === 401) {
          onUnauthorized();
          return;
        }
        if (!response.ok) throw new Error(failure(failed, "发布失败"));
      } else {
        let version = lesson.version ?? 0;
        const timeChanged =
          (!!editDate && editDate !== lesson.lessonDate) ||
          (!!editStartsAt && editStartsAt !== lesson.startsAt) ||
          (!!editEndsAt && editEndsAt !== lesson.endsAt);
        if (timeChanged) {
          if (!editDate || !editStartsAt || !editEndsAt) {
            throw new Error("改时间需要同时填写日期、开始时间与结束时间");
          }
          const { response, error: failed } = await api.POST(
            "/api/teaching/lessons/{id}/reschedule",
            {
              params: { path: { id: lesson.id! } },
              body: {
                requestId: crypto.randomUUID(),
                expectedVersion: version,
                lessonDate: editDate,
                startsAt: editStartsAt,
                endsAt: editEndsAt,
              },
            },
          );
          if (response.status === 401) {
            onUnauthorized();
            return;
          }
          if (!response.ok) throw new Error(failure(failed, "改时间失败"));
          version += 1;
        }
        if ((memo ?? "") !== (lesson.memo ?? "")) {
          const { response, error: failed } = await api.POST(
            "/api/teaching/lessons/{id}/memo",
            {
              params: { path: { id: lesson.id! } },
              body: {
                requestId: crypto.randomUUID(),
                expectedVersion: version,
                memo: memo ?? "",
              },
            },
          );
          if (response.status === 401) {
            onUnauthorized();
            return;
          }
          if (!response.ok) throw new Error(failure(failed, "保存备注失败"));
          version += 1;
        }
        const { response, error: failed } = await api.POST(
          "/api/teaching/lessons/{id}/resources",
          {
            params: { path: { id: lesson.id! } },
            body: {
              requestId: crypto.randomUUID(),
              expectedVersion: version,
              curriculumId: effectiveCurriculum,
              teacherId: teacherId ?? lesson.teacherId,
              roomId: roomId ?? lesson.roomId,
            },
          },
        );
        if (response.status === 401) {
          onUnauthorized();
          return;
        }
        if (!response.ok) throw new Error(failure(failed, "保存失败"));
      }
      onSaved();
      onClose();
    } catch (problem) {
      setError((problem as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!lesson) return;
    setDeleting(true);
    setError(undefined);
    try {
      const { response, error: failed } = await api.DELETE(
        "/api/teaching/lessons/{id}",
        {
          params: {
            path: { id: lesson.id! },
            query: { expectedVersion: lesson.version ?? 0 },
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(failure(failed, "删除课次失败"));
      onSaved();
      onClose();
    } catch (problem) {
      setError((problem as Error).message);
    } finally {
      setDeleting(false);
    }
  }

  async function searchSchemes() {
    if (!lesson?.id) return;
    setSchemeLoading(true);
    setSchemeError(undefined);
    try {
      const end = new Date(`${slot.lessonDate}T00:00:00`);
      end.setDate(end.getDate() + 6);
      const endText = end.toISOString().slice(0, 10);
      const { data, response, error: failed } = await api.POST(
        "/api/teaching/lessons/{id}/swap-schemes",
        {
          params: { path: { id: lesson.id } },
          body: { startDate: slot.lessonDate, endDate: endText },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) throw new Error(failure(failed, "搜索调课方案失败"));
      setSchemes(data);
      setSchemeIndex(0);
      setSchemeOpen(true);
      if (data.length === 0) message.info("这一周内没有找到可行的调课方案");
    } catch (problem) {
      setSchemeError((problem as Error).message);
    } finally {
      setSchemeLoading(false);
    }
  }

  /** 确认上课（全勤）：拉全班在班报名，全部按 PRESENT 提交，服务端要求覆盖全部在班报名。 */
  async function confirmPresent() {
    if (!lesson?.id) return;
    setConfirming(true);
    setError(undefined);
    try {
      const members = await api.GET("/api/teaching/classes/{id}/members", {
        params: { path: { id: classId } },
      });
      if (members.response.status === 401) {
        onUnauthorized();
        return;
      }
      const roster = members.data ?? [];
      if (!roster.length) throw new Error("该班还没有在班报名，不能确认上课");
      const { response, error: failed } = await api.POST(
        "/api/teaching/lessons/{id}/attendance",
        {
          params: { path: { id: lesson.id } },
          body: {
            expectedVersion: lesson.version,
            actualTeacherId: actualTeacherId ?? undefined,
            actualRoomId: actualRoomId ?? undefined,
            records: roster.map((item) => ({
              enrollmentId: item.enrollmentId as string,
              status: "PRESENT",
            })),
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(failure(failed, "确认上课失败"));
      message.success(`已确认上课，考勤 ${roster.length} 人全勤`);
      onSaved();
      onClose();
    } catch (problem) {
      setError((problem as Error).message);
    } finally {
      setConfirming(false);
    }
  }

  async function executeScheme() {
    const plan = schemes[schemeIndex];
    if (!lesson?.id || !plan) return;
    if (!schemeReason.trim()) {
      setSchemeError("请填写调课原因");
      return;
    }
    setSchemeExecuting(true);
    setSchemeError(undefined);
    try {
      const { response, error: failed } = await api.POST(
        "/api/teaching/lessons/{id}/swap-schemes/execute",
        {
          params: { path: { id: lesson.id } },
          body: {
            requestId: crypto.randomUUID(),
            reason: schemeReason.trim(),
            moves: plan.moves ?? [],
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(failure(failed, "执行调课方案失败"));
      message.success(`已按${plan.code ?? ""}完成 ${plan.moves?.length ?? 0} 步调课`);
      setSchemeOpen(false);
      onSaved();
      onClose();
    } catch (problem) {
      setSchemeError((problem as Error).message);
    } finally {
      setSchemeExecuting(false);
    }
  }

  return (
    <Modal
      open
      title={`${slot.className ?? ""} · ${slot.lessonDate ?? ""} ${slot.startsAt ?? ""}–${slot.endsAt ?? ""}`}
      onCancel={onClose}
      footer={[
        <Button key="close" onClick={onClose}>
          关闭
        </Button>,
        <Popconfirm
          key="delete"
          title="删除这节草稿课次？"
          description="只有草稿状态可以删除；已发布课次需先处理发布状态。"
          onConfirm={() => void remove()}
          okText="删除"
          cancelText="取消"
          disabled={locked || lesson?.status !== "DRAFT"}
        >
          <Button
            danger
            loading={deleting}
            disabled={locked || lesson?.status !== "DRAFT"}
          >
            删除课次
          </Button>
        </Popconfirm>,
        <Button
          key="publish"
          onClick={() => void save(true)}
          disabled={locked || !lesson || lesson.status !== "DRAFT"}
          loading={saving}
        >
          发布
        </Button>,
        <Button
          key="confirm"
          onClick={() => void confirmPresent()}
          disabled={locked || !lesson || lesson.status !== "PUBLISHED"}
          loading={confirming}
        >
          确认上课（全勤）
        </Button>,
        <Button
          key="save"
          type="primary"
          onClick={() => void save(false)}
          loading={saving}
          disabled={locked || !lesson}
        >
          保存设置
        </Button>,
      ]}
    >
      {lessons.isLoading ? <LoadingBlock>正在读取课次…</LoadingBlock> : null}
      {lessons.error ? (
        <Alert type="error" showIcon message={(lessons.error as Error).message} />
      ) : null}
      {lesson && locked ? (
        <Alert
          type="warning"
          showIcon
          message="该课次已确认或已关闭，不能再改课程、教师或教室"
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {error ? (
        <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />
      ) : null}
      {conflicts.data?.length ? (
        <Alert
          type="error"
          showIcon
          message={`检测到 ${conflicts.data.length} 处冲突`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {conflicts.data.map((item, index) => (
                <li key={`${item.type}-${item.otherLessonId ?? index}`}>
                  {item.message}
                  {item.otherClassName
                    ? `（${item.otherClassName} ${item.otherLessonDate ?? ""} ${item.otherStartsAt ?? ""}）`
                    : ""}
                </li>
              ))}
            </ul>
          }
          style={{ marginBottom: 12 }}
        />
      ) : null}
      <Space direction="vertical" style={{ width: "100%" }} size={12}>
        <div>
          <Typography.Text type="secondary">课程</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={effectiveCurriculum}
            onChange={setCurriculumId}
            disabled={locked}
            placeholder="选择本班开设的课程"
            options={(curricula.data ?? []).map((item) => ({
              value: item.curriculumId!,
              label: `${item.curriculumName}${item.primary ? "（主课程）" : ""} · ${item.hours ?? 0} 课时`,
            }))}
          />
        </div>
        <div>
          <Typography.Text type="secondary">教师</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={teacherId ?? lesson?.teacherId}
            onChange={setTeacherId}
            disabled={locked}
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
          <Typography.Text type="secondary">教室</Typography.Text>
          <Select
            style={{ width: "100%" }}
            value={roomId ?? lesson?.roomId}
            onChange={setRoomId}
            disabled={locked}
            allowClear
            placeholder="选择教室"
            options={(rooms.data ?? []).map((item) => ({
              value: item.id!,
              disabled: !item.available,
              label: `${item.name}${item.campusName ? ` · ${item.campusName}` : ""}${
                item.capacity ? ` · ${item.capacity} 座` : ""
              }${item.available ? "" : `（${item.reason}）`}`,
            }))}
          />
        </div>
        {lesson?.status === "PUBLISHED" ? (
          <>
            <div>
              <Typography.Text type="secondary">实际上课教师</Typography.Text>
              <Select
                style={{ width: "100%" }}
                value={actualTeacherId}
                onChange={setActualTeacherId}
                allowClear
                placeholder="默认同排课教师"
                options={(teachers.data ?? []).map((item) => ({
                  value: item.id!,
                  label: item.name!,
                }))}
              />
            </div>
            <div>
              <Typography.Text type="secondary">实际上课教室</Typography.Text>
              <Select
                style={{ width: "100%" }}
                value={actualRoomId}
                onChange={setActualRoomId}
                allowClear
                placeholder="默认同排课教室"
                options={(rooms.data ?? []).map((item) => ({
                  value: item.id!,
                  label: item.name!,
                }))}
              />
            </div>
          </>
        ) : null}
        <div>
          <Typography.Text type="secondary">上课时间</Typography.Text>
          <Space.Compact style={{ width: "100%" }}>
            <Input
              type="date"
              aria-label="上课日期"
              value={editDate}
              onChange={(event) => setEditDate(event.target.value)}
              disabled={locked}
            />
            <Input
              type="time"
              aria-label="开始时间"
              style={{ width: 110 }}
              value={editStartsAt}
              onChange={(event) => setEditStartsAt(event.target.value)}
              disabled={locked}
            />
            <Input
              type="time"
              aria-label="结束时间"
              style={{ width: 110 }}
              value={editEndsAt}
              onChange={(event) => setEditEndsAt(event.target.value)}
              disabled={locked}
            />
          </Space.Compact>
          {!locked ? (
            <Space size={4} style={{ marginTop: 6 }} wrap>
              <Typography.Text type="secondary">整节平移</Typography.Text>
              {[-30, -15, 15, 30].map((delta) => (
                <Button
                  key={delta}
                  size="small"
                  onClick={() => {
                    const start = shiftClock(editStartsAt, delta);
                    const end = shiftClock(editEndsAt, delta);
                    if (!start || !end) {
                      message.warning("平移会跨天，请手动调整日期或时间");
                      return;
                    }
                    setEditStartsAt(start);
                    setEditEndsAt(end);
                  }}
                >
                  {delta > 0 ? `+${delta}` : delta} 分钟
                </Button>
              ))}
            </Space>
          ) : null}
        </div>
        <div>
          <Typography.Text type="secondary">备注</Typography.Text>
          <Input.TextArea
            value={memo}
            onChange={(event) => setMemo(event.target.value)}
            placeholder="给老师与教务看的交接备注，最多 200 字"
            maxLength={200}
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={locked}
          />
        </div>
        <div>
          <Typography.Text type="secondary">调课</Typography.Text>
          <Button
            block
            onClick={() => void searchSchemes()}
            loading={schemeLoading}
            disabled={locked || lesson?.status !== "PUBLISHED"}
          >
            搜索调课方案（链式，最多 5 步）
          </Button>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            优先把课次挪到空闲时段；目标被占时，会先把挡路的课次挪走再挪本课次。
          </Typography.Text>
        </div>
      </Space>
      <Modal
        open={schemeOpen}
        title="调课方案"
        onCancel={() => setSchemeOpen(false)}
        footer={[
          <Button key="close" onClick={() => setSchemeOpen(false)}>
            关闭
          </Button>,
          <Button
            key="run"
            type="primary"
            loading={schemeExecuting}
            disabled={!schemes.length || !schemeReason.trim()}
            onClick={() => void executeScheme()}
          >
            执行选中方案
          </Button>,
        ]}
      >
        {schemeError ? (
          <Alert type="error" showIcon message={schemeError} style={{ marginBottom: 12 }} />
        ) : null}
        {schemes.length ? (
          <Space direction="vertical" style={{ width: "100%" }}>
            <Typography.Text type="secondary">
              共找到 {schemes.length} 个方案，按步骤数从少到多排列。
            </Typography.Text>
            <Radio.Group
              value={schemeIndex}
              onChange={(event) => setSchemeIndex(event.target.value as number)}
              style={{ width: "100%" }}
            >
              <Space direction="vertical" style={{ width: "100%" }}>
                {schemes.map((plan, index) => (
                  <Radio key={plan.code ?? index} value={index}>
                    {plan.code ?? `方案${index + 1}`} · {plan.stepCount ?? 0} 步
                    <div style={{ fontSize: 12, color: "#8c8c8c" }}>
                      {(plan.moves ?? []).map((move, step) => (
                        <div key={`${move.lessonId}-${step}`}>
                          {step + 1}. {move.className} {move.lessonDate} {move.startsAt}–
                          {move.endsAt} → {move.newLessonDate} {move.newStartsAt}–
                          {move.newEndsAt}
                        </div>
                      ))}
                    </div>
                  </Radio>
                ))}
              </Space>
            </Radio.Group>
            <div>
              <Typography.Text type="secondary">调课原因</Typography.Text>
              <Input
                value={schemeReason}
                maxLength={200}
                onChange={(event) => setSchemeReason(event.target.value)}
              />
            </div>
          </Space>
        ) : (
          <Typography.Text type="secondary">
            这一周内没有找到可行的方案，可以换一个时间段再搜。
          </Typography.Text>
        )}
      </Modal>
    </Modal>
  );
}

/**
 * 周课表（M2 → M3）：行=班级×学校时段，列=周一至周日。
 *
 * 时段来自学校级模板（旧系统 H_School_Minute_Space 的口径），班级不单独配模板；
 * 落在模板之外的课次归到该班的“其他时段”行，保证不丢课。
 */
export function TimetablePage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [schoolId, setSchoolId] = useState<string>();
  const [weekOffset, setWeekOffset] = useState(0);
  const [onlyWithLessons, setOnlyWithLessons] = useState(false);
  const [editing, setEditing] = useState<{ slot: Slot; classId: string }>();
  const [batchOpen, setBatchOpen] = useState(false);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [attendanceOpen, setAttendanceOpen] = useState(false);
  const [view, setView] = useState<"class" | "teacher" | "student" | "month">(
    "class",
  );
  const [monthOffset, setMonthOffset] = useState(0);
  const [conflictMap, setConflictMap] = useState<Record<string, LessonConflict[]>>(
    {},
  );
  const [scanning, setScanning] = useState(false);
  const { message } = AntApp.useApp();
  const [drag, setDrag] = useState<{ slot: Slot; classId: string }>();
  const [moveScheme, setMoveScheme] = useState<MoveScheme>();
  const [dragBusy, setDragBusy] = useState(false);

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

  const current = schoolId ?? capabilities.data?.schools?.[0]?.id;
  const school = capabilities.data?.schools?.find((item) => item.id === current);

  const classes = useQuery({
    queryKey: ["timetable-classes", current],
    enabled: !!current,
    queryFn: async ({ signal }): Promise<ClassRow[]> => {
      const { data, response } = await api.GET("/api/teaching/classes", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data)
        throw new Error("无法读取班级列表，请核对当前权限");
      return data;
    },
  });

  const spaces = useQuery({
    queryKey: ["timetable-minute-spaces", current],
    enabled: !!current,
    queryFn: async ({ signal }): Promise<MinuteSpace[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/schools/{schoolId}/minute-spaces",
        { params: { path: { schoolId: current! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取学校时段");
      return data;
    },
  });

  const start = useMemo(() => weekStart(weekOffset), [weekOffset]);
  const days = useMemo(
    () => Array.from({ length: 7 }, (_, index) => addDays(start, index)),
    [start],
  );

  const dayResults = useQueries({
    queries: days.map((day) => ({
      queryKey: ["timetable-day", current, isoDate(day)],
      enabled: !!current,
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        const { data, response } = await api.GET(
          "/api/teaching/reports/resource-daily-schedule",
          {
            params: {
              query: {
                schoolId: current!,
                day: isoDate(day),
                page: 0,
                pageSize: 200,
              },
            },
            signal,
          },
        );
        if (response.status === 401) {
          onUnauthorized();
          throw new Error("登录已失效，请重新登录");
        }
        if (!response.ok || !data) throw new Error("无法读取课表数据");
        return data;
      },
    })),
  });

  const classRows = useMemo(() => {
    const list = [...(classes.data ?? [])];
    list.sort((left, right) =>
      (left.name ?? "").localeCompare(right.name ?? ""),
    );
    return list;
  }, [classes.data]);

  /** 教师/学员视角：服务端按 teacher|student 聚合，行数随角色数量变化。 */
  const grouped = useQuery({
    queryKey: ["timetable-grouped", current, view, isoDate(days[0])],
    enabled: !!current && (view === "teacher" || view === "student"),
    queryFn: async ({ signal }): Promise<TimetableGroupRow[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/timetable",
        {
          params: {
            query: {
              schoolId: current!,
              from: isoDate(days[0]),
              to: isoDate(days[6]),
              view,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取角色课表");
      return data;
    },
  });

  /** 月视图：按“日”分组（view=day），前端再铺成 7 列日历。 */
  const monthFirst = useMemo(() => {
    const base = new Date();
    base.setHours(12, 0, 0, 0);
    return new Date(base.getFullYear(), base.getMonth() + monthOffset, 1);
  }, [monthOffset]);
  const monthLast = new Date(
    monthFirst.getFullYear(),
    monthFirst.getMonth() + 1,
    0,
    12,
  );
  const monthGrid = useQuery({
    queryKey: ["timetable-month", current, isoDate(monthFirst)],
    enabled: !!current && view === "month",
    queryFn: async ({ signal }): Promise<TimetableGroupRow[]> => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/timetable",
        {
          params: {
            query: {
              schoolId: current!,
              from: isoDate(monthFirst),
              to: isoDate(monthLast),
              view: "day",
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取月课表");
      return data;
    },
  });
  const monthByDay = new Map<string, TimetableCellView[]>();
  for (const row of monthGrid.data ?? [])
    if (row.key) monthByDay.set(row.key, row.cells ?? []);
  const monthGridStart = weekStartOf(monthFirst);
  const monthDays = Array.from({ length: 42 }, (_, index) =>
    addDays(monthGridStart, index),
  );

  const groupRows = grouped.data ?? [];
  const groupColumns: ColumnsType<TimetableGroupRow> = [
    {
      title: view === "student" ? "学员" : "教师",
      dataIndex: "label",
      width: 160,
      fixed: "left",
      render: (_: unknown, row: TimetableGroupRow) => (
        <div>
          <div style={{ fontWeight: 600 }}>{row.label}</div>
          <div style={{ color: "#8c8c8c", fontSize: 12 }}>
            {(row.cells ?? []).length} 节
          </div>
        </div>
      ),
    },
    ...days.map((day) => ({
      title: `${WEEKDAYS[(day.getDay() + 6) % 7]} ${isoDate(day).slice(5)}`,
      key: isoDate(day),
      width: 150,
      render: (_: unknown, row: TimetableGroupRow) => (
        <div style={{ display: "grid", gap: 6 }}>
          {(row.cells ?? [])
            .filter((cell) => cell.lessonDate === isoDate(day))
            .map((cell: TimetableCellView) => (
              <div
                key={cell.lessonId}
                style={{
                  borderLeft: `3px solid ${STATUS_COLOR[cell.status ?? ""] ?? "#d9d9d9"}`,
                  background: "#fafafa",
                  borderRadius: 4,
                  padding: "3px 6px",
                  fontSize: 12,
                  lineHeight: 1.5,
                }}
              >
                <div style={{ fontWeight: 600 }}>
                  {cell.startsAt}–{cell.endsAt}
                </div>
                <div>{cell.className}</div>
                <div style={{ color: "#595959" }}>
                  {cell.teacherName ?? "未排教师"}
                  {cell.roomName ? ` · ${cell.roomName}` : ""}
                </div>
              </div>
            ))}
        </div>
      ),
    })),
  ];

  // 一周最多几百节课次，直接按天结果聚合即可；不必为它做记忆化。
  const slotsByCell = new Map<string, Slot[]>();
  for (const result of dayResults) {
    for (const slot of result.data?.lessons ?? []) {
      const key = `${slot.className}|${slot.lessonDate}`;
      const list = slotsByCell.get(key) ?? [];
      list.push(slot);
      slotsByCell.set(key, list);
    }
  }
  for (const list of slotsByCell.values()) {
    list.sort((left, right) =>
      (left.startsAt ?? "").localeCompare(right.startsAt ?? ""),
    );
  }

  const template = spaces.data?.length ? spaces.data : FALLBACK_SPACES;
  const slots = [...slotsByCell.values()].flat();

  /** 冲突检测：对本屏可见课次逐个取冲突明细，命中即在格子里标红。 */
  async function scanConflicts() {
    setScanning(true);
    try {
      const seen = new Set<string>();
      const next: Record<string, LessonConflict[]> = {};
      for (const slot of slots) {
        const id = slot.lessonId;
        if (!id || seen.has(id)) continue;
        seen.add(id);
        const { data, response } = await api.GET(
          "/api/teaching/lessons/{id}/conflicts",
          { params: { path: { id } } },
        );
        if (response.status === 401) {
          onUnauthorized();
          return;
        }
        next[id] = data ?? [];
      }
      setConflictMap(next);
    } finally {
      setScanning(false);
    }
  }

  /** 某班的某天，落在某时段内的课次；space 为空表示“其他时段”。 */
  function cellSlots(cls: ClassRow, day: Date, space: MinuteSpace | null) {
    const list = slotsByCell.get(`${cls.name}|${isoDate(day)}`) ?? [];
    return list.filter((slot) => {
      const minute = minutesOf(slot.startsAt);
      if (Number.isNaN(minute)) return false;
      if (!space) {
        return !template.some(
          (item) => minute >= (item.beginMinute ?? 0) && minute < (item.endMinute ?? 0),
        );
      }
      return minute >= (space.beginMinute ?? 0) && minute < (space.endMinute ?? 0);
    });
  }

  type TimetableRow = {
    key: string;
    cls: ClassRow;
    space: MinuteSpace | null;
    first: boolean;
  };

  const rows: TimetableRow[] = classRows.flatMap((cls) => {
    const base = template.map((space) => ({
      key: `${cls.id}:${space.beginMinute}`,
      cls,
      space: space as MinuteSpace | null,
    }));
    const hasOther = days.some((day) => cellSlots(cls, day, null).length > 0);
    if (hasOther)
      base.push({ key: `${cls.id}:other`, cls, space: null as MinuteSpace | null });
    const visible = onlyWithLessons
      ? base.filter((row) =>
          days.some((day) => cellSlots(cls, day, row.space).length > 0),
        )
      : base;
    return visible.map((row, index) => ({ ...row, first: index === 0 }));
  });

  // 班级列纵向合并：同一班级的所有时段行只显示一次班级信息。
  const rowSpanByClass = new Map<string, number>();
  for (const row of rows)
    rowSpanByClass.set(row.cls.id!, (rowSpanByClass.get(row.cls.id!) ?? 0) + 1);

  const classCount = classRows.length;
  const classWithLessons = new Set(slots.map((slot) => slot.className)).size;
  const missingTeacher = slots.filter((slot) => !slot.teacherName).length;
  const missingRoom = slots.filter((slot) => !slot.roomName).length;
  const loading =
    dayResults.some((result) => result.isLoading) ||
    spaces.isLoading ||
    classes.isLoading;
  const failed =
    dayResults.find((result) => result.error)?.error ?? spaces.error;

  function refresh() {
    void classes.refetch();
    void spaces.refetch();
    dayResults.forEach((result) => result.refetch());
    if (view === "teacher" || view === "student") void grouped.refetch();
    if (view === "month") void monthGrid.refetch();
  }

  /** 拖拽时按“目标日期 + 目标时段起分钟”从可行性矩阵取格子状态。 */
  function schemeCell(row: TimetableRow, day: Date) {
    if (!drag || !moveScheme || row.cls.id !== drag.classId || !row.space)
      return undefined;
    const plan = moveScheme.days?.find((item) => item.date === isoDate(day));
    const startsAt = clock(row.space.beginMinute);
    return plan?.cells?.find((item) => item.startsAt === startsAt);
  }

  async function beginDrag(slot: Slot, classId: string) {
    if (slot.status !== "PUBLISHED") {
      void message.warning("只有已发布的课次可以拖动调课");
      return;
    }
    setDrag({ slot, classId });
    setMoveScheme(undefined);
    try {
      const { data, response, error: failed } = await api.POST(
        "/api/teaching/lessons/{id}/move-scheme",
        {
          params: { path: { id: slot.lessonId! } },
          body: { startDate: isoDate(days[0]), endDate: isoDate(days[6]) },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data)
        throw new Error(failure(failed, "无法计算可放位置"));
      setMoveScheme(data);
    } catch (problem) {
      void message.error((problem as Error).message);
    }
  }

  function endDrag() {
    setDrag(undefined);
    setMoveScheme(undefined);
  }

  /** 资源日报只给课次摘要，不含 version；落库前按班查一次完整课次。 */
  async function lessonVersion(classId: string, lessonId: string) {
    const { data, response } = await api.GET("/api/teaching/lessons", {
      params: { query: { classId } },
    });
    if (response.status === 401) {
      onUnauthorized();
      throw new Error("登录已失效，请重新登录");
    }
    const found = (data ?? []).find((item) => item.id === lessonId);
    if (!found) throw new Error("没有找到该课次，可能已被删除");
    return found.version ?? 0;
  }

  async function dropOn(row: TimetableRow, day: Date) {
    if (!drag || dragBusy) return;
    const cell = schemeCell(row, day);
    const sourceId = drag.slot.lessonId;
    if (!cell || !sourceId) return;
    if (cell.status !== "FREE" && cell.status !== "CLASS_OCCUPIED") {
      void message.error(cell.reason || "该位置不可放");
      return;
    }
    setDragBusy(true);
    try {
      const sourceVersion = await lessonVersion(drag.classId, sourceId);
      if (cell.status === "FREE") {
        const { response, error: failed } = await api.POST(
          "/api/teaching/lessons/{id}/reschedule",
          {
            params: { path: { id: sourceId } },
            body: {
              requestId: crypto.randomUUID(),
              expectedVersion: sourceVersion,
              lessonDate: isoDate(day),
              startsAt: cell.startsAt!,
              endsAt: cell.endsAt!,
              reason: "拖拽调课",
            },
          },
        );
        if (response.status === 401) {
          onUnauthorized();
          return;
        }
        if (!response.ok) throw new Error(failure(failed, "调课失败"));
        void message.success("课次已移动");
      } else {
        const target = cellSlots(row.cls, day, row.space).find(
          (item) => item.startsAt === cell.startsAt,
        );
        if (!target?.lessonId) throw new Error("目标格子里没有可交换的课次");
        if (target.status !== "PUBLISHED")
          throw new Error("目标课次尚未发布，不能交换");
        const targetVersion = await lessonVersion(row.cls.id!, target.lessonId);
        const { response, error: failed } = await api.POST(
          "/api/teaching/lessons/{id}/swap",
          {
            params: { path: { id: sourceId } },
            body: {
              requestId: crypto.randomUUID(),
              otherLessonId: target.lessonId,
              expectedVersion: sourceVersion,
              expectedOtherVersion: targetVersion,
              reason: "拖拽交换课次",
            },
          },
        );
        if (response.status === 401) {
          onUnauthorized();
          return;
        }
        if (!response.ok) throw new Error(failure(failed, "交换课次失败"));
        void message.success("两节课已交换");
      }
      endDrag();
      refresh();
    } catch (problem) {
      void message.error((problem as Error).message);
    } finally {
      setDragBusy(false);
    }
  }

  if (capabilities.isLoading)
    return <LoadingBlock>正在确认可用的学校范围…</LoadingBlock>;
  if (capabilities.error)
    return (
      <Page>
        <PageHeader title="排课" description="按周查看班级课表。" />
        <Panel>
          <StateBlock
            title="教务权限没有读取成功"
            hint={(capabilities.error as Error).message}
            action={
              <Button onClick={() => void capabilities.refetch()}>重试</Button>
            }
          />
        </Panel>
      </Page>
    );

  if (!current)
    return (
      <Page>
        <PageHeader title="排课" description="按周查看班级课表。" />
        <Panel>
          <StateBlock
            title="当前账号还没有可查看的学校"
            hint="排课按学校范围取数；请联系机构管理员分配学校，或先在组织目录建校。"
          />
        </Panel>
      </Page>
    );

  const columns: ColumnsType<TimetableRow> = [
    {
      title: "班级 / 时段",
      key: "class",
      fixed: "left" as const,
      width: 210,
      onCell: (row: TimetableRow) => ({
        rowSpan: row.first ? (rowSpanByClass.get(row.cls.id!) ?? 1) : 0,
      }),
      render: (_: unknown, row: TimetableRow) =>
        row.first ? (
          <div>
            <div style={{ fontWeight: 600 }}>{row.cls.name}</div>
            <div style={{ color: "#8c8c8c", fontSize: 12 }}>
              {row.cls.curriculumName || "未设课程"}
              {typeof row.cls.memberCount === "number"
                ? ` · 在班 ${row.cls.memberCount}${row.cls.capacity ? `/${row.cls.capacity}` : ""} 人`
                : ""}
            </div>
            <Tag style={{ marginTop: 4 }}>
              {row.cls.status === "ACTIVE" ? "在读" : "已结班"}
            </Tag>
          </div>
        ) : null,
    },
    {
      title: "时段",
      key: "space",
      width: 100,
      render: (_: unknown, row: TimetableRow) => (
        <span style={{ fontSize: 12, color: "#595959" }}>
          {row.space
            ? `${clock(row.space.beginMinute)}–${clock(row.space.endMinute)}`
            : "其他时段"}
        </span>
      ),
    },
    ...days.map((day, index) => ({
      title: (
        <div>
          <div>{WEEKDAYS[index]}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {isoDate(day).slice(5)}
          </Typography.Text>
        </div>
      ),
      key: isoDate(day),
      width: 140,
      render: (_: unknown, row: TimetableRow) => {
        const list = cellSlots(row.cls, day, row.space);
        const cell = schemeCell(row, day);
        const droppable =
          !!cell && (cell.status === "FREE" || cell.status === "CLASS_OCCUPIED");
        return (
          <div
            onDragOver={(event) => {
              if (!droppable) return;
              event.preventDefault();
              event.dataTransfer.dropEffect = "move";
            }}
            onDrop={(event) => {
              if (!droppable) return;
              event.preventDefault();
              void dropOn(row, day);
            }}
            style={{
              minHeight: 36,
              borderRadius: 4,
              padding: 2,
              background: cell ? CELL_BG[cell.status ?? "OTHER"] : undefined,
              outline:
                cell?.status === "FREE" ? "1px dashed #52c41a" : undefined,
              opacity: drag && row.cls.id !== drag.classId ? 0.55 : 1,
            }}
            title={cell?.reason}
          >
            {list.length ? (
              list.map((slot) => (
                <SlotChip
                  key={slot.lessonId}
                  slot={slot}
                  conflicts={conflictMap[slot.lessonId ?? ""]}
                  dragging={drag?.slot.lessonId === slot.lessonId}
                  onDragStart={(next) => void beginDrag(next, row.cls.id!)}
                  onDragEnd={endDrag}
                  onOpen={(next) =>
                    setEditing({ slot: next, classId: row.cls.id! })
                  }
                />
              ))
            ) : (
              <span style={{ color: "#bfbfbf", fontSize: 12 }}>—</span>
            )}
          </div>
        );
      },
    })),
  ];

  return (
    <Page>
      <PageHeader
        title="排课"
        description="按班级、教师、学员或整月查看课表；点击课次可设置课程、教师、教室与备注，拖动已发布课次到绿格调课、落到其他课次上交换。"
        actions={
          capabilities.data?.schools && capabilities.data.schools.length > 1 ? (
            <Select
              aria-label="选择学校"
              style={{ minWidth: 180 }}
              value={current}
              onChange={setSchoolId}
              options={capabilities.data.schools.map((item) => ({
                value: item.id,
                label: item.name,
              }))}
            />
          ) : (
            <Typography.Text type="secondary">{school?.name}</Typography.Text>
          )
        }
      />

      <Toolbar>
        <Space wrap>
          {view === "month" ? (
            <>
              <Button onClick={() => setMonthOffset((value) => value - 1)}>
                上一月
              </Button>
              <Button
                onClick={() => setMonthOffset(0)}
                disabled={monthOffset === 0}
              >
                本月
              </Button>
              <Button onClick={() => setMonthOffset((value) => value + 1)}>
                下一月
              </Button>
              <Typography.Text>
                {monthFirst.getFullYear()} 年 {monthFirst.getMonth() + 1} 月
              </Typography.Text>
            </>
          ) : (
            <>
              <Button onClick={() => setWeekOffset((value) => value - 1)}>
                上一周
              </Button>
              <Button
                onClick={() => setWeekOffset(0)}
                disabled={weekOffset === 0}
              >
                本周
              </Button>
              <Button onClick={() => setWeekOffset((value) => value + 1)}>
                下一周
              </Button>
              <Typography.Text>
                {isoDate(days[0])} 至 {isoDate(days[6])}
              </Typography.Text>
            </>
          )}
          <Checkbox
            checked={onlyWithLessons}
            onChange={(event) => setOnlyWithLessons(event.target.checked)}
          >
            只看有课
          </Checkbox>
          <Select
            value={view}
            onChange={(value) => setView(value)}
            style={{ width: 130 }}
            options={[
              { value: "class", label: "按班级" },
              { value: "teacher", label: "按教师" },
              { value: "student", label: "按学员" },
              { value: "month", label: "按月" },
            ]}
          />
          <Button onClick={refresh} loading={loading}>
            刷新
          </Button>
          <NotificationBell schoolId={current} />
          <Button onClick={() => setBatchOpen(true)} disabled={!classCount}>
            添加课次
          </Button>
          <Button onClick={() => setBulkOpen(true)} disabled={!classCount}>
            批量操作
          </Button>
          <Button onClick={() => setAttendanceOpen(true)} disabled={!classCount}>
            录考勤
          </Button>
          <Button
            onClick={() => void scanConflicts()}
            loading={scanning}
            disabled={!slots.length}
          >
            冲突检测
          </Button>
        </Space>
      </Toolbar>

      <StatStrip
        items={[
          { label: "本周课次", value: slots.length },
          {
            label: "有课班级",
            value: `${classWithLessons}/${classCount}`,
            detail: "至少有一节课的班级",
          },
          {
            label: "缺教师",
            value: missingTeacher,
            detail: missingTeacher ? "需要补排教师" : "已排齐",
          },
          {
            label: "缺教室",
            value: missingRoom,
            detail: missingRoom ? "需要补排教室" : "已排齐",
          },
        ]}
      />

      {failed ? (
        <Alert
          type="error"
          showIcon
          message="课表取数失败"
          description={(failed as Error).message}
          style={{ marginBottom: 12 }}
        />
      ) : null}

      {view === "teacher" || view === "student" ? (
        <Panel ariaLabel="按角色课表">
          {view === "teacher" ? (
            <TeacherReassignBar
              schoolId={current}
              from={isoDate(weekStartOf(new Date()))}
              to={isoDate(addDays(weekStartOf(new Date()), 6))}
              onDone={() => void grouped.refetch()}
            />
          ) : null}
          {grouped.isLoading ? (
            <LoadingBlock>正在读取课表…</LoadingBlock>
          ) : grouped.error ? (
            <StateBlock
              title="角色课表没有读取成功"
              hint={(grouped.error as Error).message}
              action={<Button onClick={() => void grouped.refetch()}>重试</Button>}
            />
          ) : groupRows.length === 0 ? (
            <StateBlock
              title={view === "teacher" ? "这一周没有教师排课" : "这一周没有学员上课"}
              hint="只有已排教师/已进班的课次会出现在这里；先在学校范围内排课或进班。"
            />
          ) : (
            <Table
              rowKey="key"
              size="small"
              bordered
              loading={grouped.isLoading}
              dataSource={groupRows}
              columns={groupColumns}
              pagination={false}
              scroll={{ x: 1200 }}
              locale={{ emptyText: "没有符合条件的课次" }}
            />
          )}
        </Panel>
      ) : null}

      {view === "month" ? (
        <Panel ariaLabel="按月课表">
          {monthGrid.isLoading ? (
            <LoadingBlock>正在读取月课表…</LoadingBlock>
          ) : monthGrid.error ? (
            <StateBlock
              title="月课表没有读取成功"
              hint={(monthGrid.error as Error).message}
              action={
                <Button onClick={() => void monthGrid.refetch()}>重试</Button>
              }
            />
          ) : (
            <>
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(7, minmax(0, 1fr))",
                  gap: 4,
                  marginBottom: 6,
                }}
              >
                {WEEKDAYS.map((day) => (
                  <div
                    key={day}
                    style={{
                      fontSize: 12,
                      color: "#8c8c8c",
                      textAlign: "center",
                    }}
                  >
                    {day}
                  </div>
                ))}
              </div>
              {Array.from({ length: 6 }, (_, week) => (
                <div
                  key={week}
                  style={{
                    display: "grid",
                    gridTemplateColumns: "repeat(7, minmax(0, 1fr))",
                    gap: 4,
                    marginBottom: 4,
                  }}
                >
                  {monthDays.slice(week * 7, week * 7 + 7).map((day) => {
                    const key = isoDate(day);
                    const cells = monthByDay.get(key) ?? [];
                    const inMonth = day.getMonth() === monthFirst.getMonth();
                    return (
                      <div
                        key={key}
                        style={{
                          minHeight: 96,
                          border: "1px solid #f0f0f0",
                          borderRadius: 6,
                          padding: 4,
                          background: inMonth ? "#fff" : "#fafafa",
                          opacity: inMonth ? 1 : 0.55,
                        }}
                      >
                        <div
                          style={{
                            display: "flex",
                            justifyContent: "space-between",
                            fontSize: 12,
                            color: "#8c8c8c",
                            marginBottom: 4,
                          }}
                        >
                          <span>{day.getDate()}</span>
                          <span>{cells.length || ""}</span>
                        </div>
                        <div style={{ display: "grid", gap: 4 }}>
                          {cells.map((cell) => (
                            <button
                              key={cell.lessonId}
                              type="button"
                              onClick={() =>
                                setEditing({
                                  classId: cell.classId!,
                                  slot: cell as unknown as Slot,
                                })
                              }
                              style={{
                                textAlign: "left",
                                cursor: "pointer",
                                border: "none",
                                borderLeft: `3px solid ${
                                  STATUS_COLOR[cell.status ?? ""] ?? "#d9d9d9"
                                }`,
                                background: "#fafafa",
                                borderRadius: 4,
                                padding: "2px 4px",
                                fontSize: 12,
                                lineHeight: 1.4,
                              }}
                              title={`${cell.className ?? ""} ${cell.startsAt ?? ""}-${cell.endsAt ?? ""} ${cell.teacherName ?? ""}`}
                            >
                              <div style={{ fontWeight: 600 }}>
                                {cell.startsAt}
                              </div>
                              <div
                                style={{
                                  overflow: "hidden",
                                  textOverflow: "ellipsis",
                                  whiteSpace: "nowrap",
                                }}
                              >
                                {cell.className}
                              </div>
                            </button>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ))}
            </>
          )}
        </Panel>
      ) : null}

      {view === "class" ? (
      <Panel ariaLabel="按周课表">
        {classes.isLoading ? (
          <LoadingBlock>正在读取班级…</LoadingBlock>
        ) : classes.error ? (
          <StateBlock
            title="班级列表没有读取成功"
            hint={(classes.error as Error).message}
            action={<Button onClick={() => void classes.refetch()}>重试</Button>}
          />
        ) : classCount === 0 ? (
          <StateBlock
            title="这所学校还没有班级"
            hint="排课以班级为单位；先在「教务与考勤」建班并导入学员，再回到这里看周课表。"
          />
        ) : (
          <>
            {!spaces.data?.length ? (
              <Alert
                type="info"
                showIcon
                message="这所学校还没有配置时段模板"
                description="当前按演示用的三段时段兜底显示；在「教务与考勤」配置学校时段后，行会按模板切分。"
                style={{ marginBottom: 12 }}
              />
            ) : null}
            {!loading && slots.length === 0 ? (
              <Alert
                type="info"
                showIcon
                message="这一周没有排课"
                description="切换到本周或下一周；需要排课时请到「教务与考勤」的班级行使用自动排课。"
                style={{ marginBottom: 12 }}
              />
            ) : null}
            <Table
              rowKey="key"
              size="small"
              bordered
              loading={loading}
              dataSource={rows}
              columns={columns}
              pagination={false}
              scroll={{ x: 1200 }}
              locale={{ emptyText: "没有符合条件的班级" }}
            />
          </>
        )}
      </Panel>
      ) : null}

      {editing ? (
        <LessonResourceDialog
          slot={editing.slot}
          classId={editing.classId}
          onClose={() => setEditing(undefined)}
          onUnauthorized={onUnauthorized}
          onSaved={refresh}
        />
      ) : null}

      {batchOpen ? (
        <BatchLessonDialog
          classes={classRows.filter((item) => item.status === "ACTIVE")}
          onClose={() => setBatchOpen(false)}
          onUnauthorized={onUnauthorized}
          onCreated={refresh}
        />
      ) : null}

      <BatchAttendanceDialog
        open={attendanceOpen}
        classes={classRows
          .filter((item) => item.id && item.name)
          .map((item) => ({ id: item.id as string, name: item.name as string }))}
        weekStart={isoDate(days[0])}
        weekEnd={isoDate(days[6])}
        onClose={() => setAttendanceOpen(false)}
        onDone={refresh}
        onUnauthorized={onUnauthorized}
      />

      <ClassLessonBatchDialog
        open={bulkOpen}
        classes={classRows
          .filter((item) => item.id && item.name)
          .map((item) => ({ id: item.id as string, name: item.name as string }))}
        weekStart={isoDate(days[0])}
        weekEnd={isoDate(days[6])}
        onClose={() => setBulkOpen(false)}
        onDone={refresh}
        onUnauthorized={onUnauthorized}
      />
    </Page>
  );
}
