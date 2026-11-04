import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf, downloadCsv } from "../../shared/http";
import { SchedulePlanner } from "./SchedulePlanner";
import { BatchSchedulePlanner } from "./BatchSchedulePlanner";
import { SchedulingSettings } from "./SchedulingSettings";
import type { components } from "../../shared/generated/api";

type LessonView = components["schemas"]["LessonView"];
type ClassMemberView = components["schemas"]["ClassMemberView"];
type Curriculum = components["schemas"]["Curriculum"];
type TeacherWorkbench = components["schemas"]["TeacherWorkbench"];
type TeacherClassWorkbench = components["schemas"]["TeacherClassWorkbench"];
type TeacherWorkbenchClass = NonNullable<
  NonNullable<TeacherWorkbench["classes"]>[number]
>;
type TeacherWorkbenchLesson = NonNullable<
  NonNullable<TeacherWorkbench["lessons"]>[number]
>;
type DailyResourceSchedule = components["schemas"]["DailyResourceSchedule"];

function localDate(offsetDays: number) {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

const LESSON_STATUS: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  CONFIRMED: "已确认",
  CLOSED: "已关闭",
};

function lessonDurationMinutes(lesson: LessonView) {
  const minutes = (value: string | null | undefined) => {
    const [hours, remainder] = (value ?? "").slice(0, 5).split(":").map(Number);
    return hours * 60 + remainder;
  };
  return minutes(lesson.endsAt) - minutes(lesson.startsAt);
}

/**
 * 教务工作台：班级 → 课次 → 发布 → 考勤确认 → 关闭课次（唯一消费固化点）。
 *
 * 页面对话只提交状态与版本；消费金额由服务端按报名冻结单价计算，页面只展示对账结果。
 */
export function TeachingWorkspace({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [classId, setClassId] = useState<string>();
  const [creatingClass, setCreatingClass] = useState(false);
  const [creatingLesson, setCreatingLesson] = useState(false);
  const [scheduling, setScheduling] = useState(false);
  const [batchScheduling, setBatchScheduling] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [attendanceFor, setAttendanceFor] = useState<LessonView | null>(null);
  const [reconcileFor, setReconcileFor] = useState<string | null>(null);
  const [teacherClassId, setTeacherClassId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [workbenchFrom, setWorkbenchFrom] = useState(() => localDate(0));
  const [workbenchTo, setWorkbenchTo] = useState(() => localDate(30));
  const [resourceDay, setResourceDay] = useState(() => localDate(0));
  const [resourcePage, setResourcePage] = useState(1);
  const cancelRequests = useRef(
    new Map<string, { reason: string; version: number; requestId: string }>(),
  );
  const queryClient = useQueryClient();

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
  const school = capabilities.data?.schools?.find(
    (item) => item.id === current,
  );
  const classes = useQuery({
    queryKey: ["teaching-classes", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
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
  const curricula = useQuery({
    queryKey: ["teaching-curricula", current],
    enabled: !!current && !!school?.maintainable,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/product/catalog", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data.curricula ?? [];
    },
  });
  const lessons = useQuery({
    queryKey: ["teaching-lessons", classId],
    enabled: !!classId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/teaching/lessons", {
        params: { query: { classId: classId! } },
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取课次");
      return data;
    },
  });
  const members = useQuery({
    queryKey: ["teaching-members", classId],
    enabled: !!classId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/classes/{id}/members",
        {
          params: { path: { id: classId! } },
          signal,
        },
      );
      if (!response.ok || !data) return [];
      return data;
    },
  });
  const teacherWorkbench = useQuery({
    queryKey: ["teacher-workbench", workbenchFrom, workbenchTo],
    enabled: !!capabilities.data?.canRead,
    queryFn: async ({ signal }): Promise<TeacherWorkbench> => {
      const { data, response } = await api.GET("/api/teaching/me/workbench", {
        params: { query: { from: workbenchFrom, to: workbenchTo } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取教师本人课表");
      return data;
    },
  });
  const teacherClassWorkbench = useQuery({
    queryKey: ["teacher-class-workbench", teacherClassId],
    enabled: !!teacherClassId,
    queryFn: async ({ signal }): Promise<TeacherClassWorkbench> => {
      const { data, response } = await api.GET(
        "/api/teaching/me/classes/{id}",
        { params: { path: { id: teacherClassId! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取本人班级工作台");
      return data;
    },
  });
  const resourceSchedule = useQuery({
    queryKey: [
      "teaching-resource-daily-schedule",
      current,
      resourceDay,
      resourcePage,
    ],
    enabled: !!current && !!capabilities.data?.canRead,
    queryFn: async ({ signal }): Promise<DailyResourceSchedule> => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/resource-daily-schedule",
        {
          params: {
            query: {
              schoolId: current!,
              day: resourceDay,
              page: resourcePage - 1,
              pageSize: 100,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取资源日程");
      return data;
    },
  });

  async function closeClass(row: components["schemas"]["ClassView"]) {
    if (!row.id) return;
    const classId = row.id;
    Modal.confirm({
      title: "确认结班？",
      content: "结班会结束该班的在班关系；存在未关闭课次时操作会被拒绝。",
      okText: "确认结班",
      cancelText: "取消",
      onOk: async () => {
        setError("");
        try {
          const token = await csrf();
          const { response } = await api.POST(
            "/api/teaching/classes/{id}/close",
            {
              params: { path: { id: classId } },
              headers: { [token.headerName!]: token.token! },
              body: { expectedVersion: row.version ?? 0 },
            },
          );
          if (response.status === 401) {
            onUnauthorized();
            return;
          }
          if (!response.ok) {
            setError(
              response.status === 409
                ? "班级版本或课次状态已变化，请刷新后核对。"
                : "结班失败，请核对权限及未关闭课次。",
            );
            return;
          }
          await Promise.all([
            queryClient.invalidateQueries({ queryKey: ["teaching-classes"] }),
            queryClient.invalidateQueries({
              queryKey: ["teaching-members", classId],
            }),
          ]);
        } catch {
          setError("结班结果未确认，请刷新班级和课次后核对。");
        }
      },
    });
  }

  async function cancelClass(row: components["schemas"]["ClassView"]) {
    if (!row.id) return;
    const classId = row.id;
    const version = row.version ?? 0;
    Modal.confirm({
      title: "确认取消空班？",
      content: "仅允许没有课次且没有在班学员的班级取消；该操作会保留取消记录。",
      okText: "确认取消",
      cancelText: "返回",
      onOk: async () => {
        const reason = window.prompt("请输入取消原因")?.trim();
        if (!reason) return;
        let request = cancelRequests.current.get(classId);
        if (
          !request ||
          request.reason !== reason ||
          request.version !== version
        ) {
          request = { reason, version, requestId: crypto.randomUUID() };
          cancelRequests.current.set(classId, request);
        }
        setError("");
        try {
          const token = await csrf();
          const { response } = await api.POST(
            "/api/teaching/classes/{id}/cancel",
            {
              params: { path: { id: classId } },
              headers: { [token.headerName!]: token.token! },
              body: {
                requestId: request.requestId,
                expectedVersion: version,
                reason,
              },
            },
          );
          if (response.status === 401) {
            onUnauthorized();
            return;
          }
          if (!response.ok) {
            setError(
              response.status === 409
                ? "班级状态已变化或不符合空班条件，请刷新后核对。"
                : "取消班级失败，请核对维护权限。",
            );
            return;
          }
          cancelRequests.current.delete(classId);
          await queryClient.invalidateQueries({
            queryKey: ["teaching-classes", current],
          });
        } catch {
          setError(
            "取消结果未确认；可用相同原因重试，系统会按请求标识防止重复执行。",
          );
        }
      },
    });
  }

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取教务权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无教务读取权限" type="error" />;
  const schools = capabilities.data.schools ?? [];
  if (schools.length === 0)
    return <Alert title="当前范围内没有可查看的学校" type="warning" />;

  return (
    <>
      <Space className="toolbar" wrap>
        <Select
          aria-label="归属学校"
          style={{ width: 200 }}
          value={current}
          options={schools.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
          onChange={(value) => {
            setSchoolId(value);
            setClassId(undefined);
          }}
        />
        {school?.maintainable ? (
          <>
            <Button onClick={() => setCreatingClass(true)}>新建班级</Button>
            <Button onClick={() => setBatchScheduling(true)}>
              多班自动排课
            </Button>
          </>
        ) : null}
        <Button onClick={() => setSettingsOpen(true)}>排课设置</Button>
        <Button
          onClick={() => void classes.refetch()}
          loading={classes.isFetching}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">
          关闭课次才固化消费；草稿与发布不产生课时消耗
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Card
        title="教师本人课表与班级工作台"
        size="small"
        style={{ marginBottom: 12 }}
      >
        <Space wrap style={{ marginBottom: 12 }}>
          <Input
            aria-label="教师课表开始日期"
            type="date"
            value={workbenchFrom}
            onChange={(event) => setWorkbenchFrom(event.target.value)}
            style={{ width: 170 }}
          />
          <Input
            aria-label="教师课表结束日期"
            type="date"
            value={workbenchTo}
            onChange={(event) => setWorkbenchTo(event.target.value)}
            style={{ width: 170 }}
          />
          <Button
            onClick={() => void teacherWorkbench.refetch()}
            loading={teacherWorkbench.isFetching}
          >
            刷新本人课表
          </Button>
        </Space>
        {teacherWorkbench.error ? (
          <Alert type="error" title={teacherWorkbench.error.message} showIcon />
        ) : null}
        <Table
          rowKey="id"
          size="small"
          loading={teacherWorkbench.isFetching}
          dataSource={teacherWorkbench.data?.lessons ?? []}
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
          {(teacherWorkbench.data?.classes ?? [])
            .map((item) => `${item.name}（${item.lessonCount} 次）`)
            .join("、") || "暂无"}
        </Typography.Text>
        <Table
          rowKey="id"
          size="small"
          style={{ marginTop: 8 }}
          dataSource={teacherWorkbench.data?.classes ?? []}
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
                    if (row.id) setTeacherClassId(row.id);
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
      <Drawer
        title={teacherClassWorkbench.data?.classInfo?.name ?? "本人班级工作台"}
        open={!!teacherClassId}
        size="large"
        onClose={() => setTeacherClassId(null)}
      >
        {teacherClassWorkbench.error ? (
          <Alert
            type="error"
            title={teacherClassWorkbench.error.message}
            showIcon
          />
        ) : null}
        <Descriptions
          size="small"
          column={2}
          items={[
            {
              key: "curriculum",
              label: "课程",
              children: teacherClassWorkbench.data?.classInfo?.curriculumName,
            },
            {
              key: "status",
              label: "班级状态",
              children: teacherClassWorkbench.data?.classInfo?.status,
            },
            {
              key: "group",
              label: "团培",
              children: teacherClassWorkbench.data?.classInfo?.groupTraining
                ? "是"
                : "否",
            },
          ]}
        />
        <Typography.Title level={5}>班级名册</Typography.Title>
        <Table
          rowKey="enrollmentId"
          size="small"
          loading={teacherClassWorkbench.isFetching}
          dataSource={teacherClassWorkbench.data?.members ?? []}
          pagination={false}
          columns={[
            { title: "学员", dataIndex: "studentName" },
            { title: "入班日期", dataIndex: "joinedOn" },
            { title: "状态", dataIndex: "status" },
          ]}
        />
        <Typography.Title level={5}>本人授课课次</Typography.Title>
        <Table
          rowKey="id"
          size="small"
          loading={teacherClassWorkbench.isFetching}
          dataSource={teacherClassWorkbench.data?.lessons ?? []}
          pagination={false}
          columns={[
            { title: "日期", dataIndex: "lessonDate" },
            {
              title: "时间",
              render: (_: unknown, row: TeacherWorkbenchLesson) =>
                `${row.startsAt}–${row.endsAt}`,
            },
            { title: "课题", dataIndex: "topic" },
            { title: "教室", dataIndex: "roomName" },
            {
              title: "状态",
              dataIndex: "status",
              render: (status: string) => LESSON_STATUS[status] ?? status,
            },
          ]}
        />
      </Drawer>
      <Card title="资源日程日报" size="small" style={{ marginBottom: 12 }}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Input
            aria-label="资源日程日期"
            type="date"
            value={resourceDay}
            onChange={(event) => {
              setResourceDay(event.target.value);
              setResourcePage(1);
            }}
            style={{ width: 170 }}
          />
          <Button
            onClick={() => void resourceSchedule.refetch()}
            loading={resourceSchedule.isFetching}
          >
            刷新资源日程
          </Button>
          <Typography.Text type="secondary">
            未分配教师 {resourceSchedule.data?.missingTeacher ?? "—"}
            ，未分配教室 {resourceSchedule.data?.missingRoom ?? "—"}
          </Typography.Text>
        </Space>
        {resourceSchedule.error ? (
          <Alert type="error" title={resourceSchedule.error.message} showIcon />
        ) : null}
        <Table
          rowKey="lessonId"
          size="small"
          loading={resourceSchedule.isFetching}
          dataSource={resourceSchedule.data?.lessons ?? []}
          pagination={{
            current: resourcePage,
            pageSize: 100,
            total: resourceSchedule.data?.total ?? 0,
            onChange: setResourcePage,
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
      {classes.error ? (
        <Alert title={classes.error.message} type="error" />
      ) : null}
      <Card title="班级" size="small">
        <Table
          rowKey="id"
          size="small"
          loading={classes.isPending}
          dataSource={classes.data ?? []}
          pagination={false}
          onRow={(row) => ({
            onClick: () => setClassId(row.id ?? undefined),
            style: { cursor: "pointer" },
          })}
          columns={[
            { title: "班级", dataIndex: "name" },
            { title: "课程", dataIndex: "curriculumName" },
            {
              title: "类型",
              dataIndex: "groupTraining",
              render: (groupTraining: boolean) =>
                groupTraining ? <Tag color="blue">团培</Tag> : <Tag>普通</Tag>,
            },
            { title: "在班人数", dataIndex: "memberCount" },
            {
              title: "状态",
              dataIndex: "status",
              render: (status: string) =>
                status === "ACTIVE" ? (
                  <Tag color="green">进行中</Tag>
                ) : status === "CANCELLED" ? (
                  <Tag color="default">已取消</Tag>
                ) : (
                  <Tag>已结班</Tag>
                ),
            },
            {
              title: "操作",
              render: (_: unknown, row: components["schemas"]["ClassView"]) =>
                school?.maintainable && row.status === "ACTIVE" ? (
                  <Space>
                    <Button
                      size="small"
                      danger
                      onClick={(event) => {
                        event.stopPropagation();
                        closeClass(row);
                      }}
                    >
                      结班
                    </Button>
                    {row.memberCount === 0 ? (
                      <Button
                        size="small"
                        onClick={(event) => {
                          event.stopPropagation();
                          cancelClass(row);
                        }}
                      >
                        取消空班
                      </Button>
                    ) : null}
                  </Space>
                ) : null,
            },
          ]}
          locale={{ emptyText: "该学校还没有班级" }}
        />
      </Card>
      {classId ? (
        <Card
          title={`课次：${classes.data?.find((item) => item.id === classId)?.name ?? ""}`}
          size="small"
          style={{ marginTop: 16 }}
        >
          <Space wrap style={{ marginBottom: 12 }}>
            {school?.maintainable ? (
              <>
                <Button onClick={() => setCreatingLesson(true)}>
                  新建课次
                </Button>
                <Button onClick={() => setScheduling(true)}>自动排课</Button>
              </>
            ) : null}
            <Button
              onClick={() => void lessons.refetch()}
              loading={lessons.isFetching}
            >
              刷新课次
            </Button>
            <Button
              onClick={() =>
                void downloadCsv(
                  `/api/teaching/lessons/export?schoolId=${encodeURIComponent(current ?? "")}`,
                  "lessons.csv",
                )
              }
              disabled={!current}
            >
              导出课次
            </Button>
            <Typography.Text type="secondary">
              在班 {members.data?.length ?? 0} 人
            </Typography.Text>
          </Space>
          <Table
            rowKey="id"
            size="small"
            loading={lessons.isPending}
            dataSource={lessons.data ?? []}
            pagination={false}
            columns={[
              { title: "日期", dataIndex: "lessonDate" },
              {
                title: "时间",
                render: (_: unknown, row: LessonView) =>
                  `${row.startsAt}–${row.endsAt}`,
              },
              { title: "课题", dataIndex: "topic" },
              { title: "课时", dataIndex: "hours" },
              {
                title: "状态",
                dataIndex: "status",
                render: (status: string) => (
                  <Tag>{LESSON_STATUS[status] ?? status}</Tag>
                ),
              },
              { title: "考勤", dataIndex: "attendanceCount" },
              {
                title: "操作",
                key: "actions",
                render: (_: unknown, row: LessonView) => (
                  <Space>
                    {row.status === "DRAFT" ? (
                      <PublishLessonButton
                        lesson={row}
                        onDone={() => void lessons.refetch()}
                        onError={setError}
                      />
                    ) : null}
                    {row.status === "PUBLISHED" ? (
                      <Button
                        size="small"
                        onClick={() => setAttendanceFor(row)}
                      >
                        考勤确认
                      </Button>
                    ) : null}
                    {row.status === "CONFIRMED" && school?.canCloseLesson ? (
                      <CloseLessonButton
                        lesson={row}
                        onDone={() => void lessons.refetch()}
                        onError={setError}
                      />
                    ) : null}
                  </Space>
                ),
              },
            ]}
            locale={{ emptyText: "该班级还没有课次" }}
          />
          <Typography.Title level={5} style={{ marginTop: 16 }}>
            在班报名与对账
          </Typography.Title>
          <Table
            rowKey="enrollmentId"
            size="small"
            dataSource={members.data ?? []}
            pagination={false}
            columns={[
              { title: "学员", dataIndex: "studentName" },
              { title: "入班日期", dataIndex: "joinedOn" },
              {
                title: "操作",
                key: "actions",
                render: (_: unknown, member: ClassMemberView) => (
                  <Button
                    size="small"
                    onClick={() => setReconcileFor(member.enrollmentId ?? null)}
                  >
                    课时对账
                  </Button>
                ),
              },
            ]}
            locale={{ emptyText: "该班级还没有报名" }}
          />
        </Card>
      ) : null}
      {creatingClass && current ? (
        <ClassCreator
          schoolId={current}
          curricula={curricula.data ?? []}
          onCancel={() => setCreatingClass(false)}
          onCreated={() => {
            setCreatingClass(false);
            void classes.refetch();
          }}
        />
      ) : null}
      {creatingLesson && classId ? (
        <LessonCreator
          classId={classId}
          onCancel={() => setCreatingLesson(false)}
          onCreated={() => {
            setCreatingLesson(false);
            void lessons.refetch();
          }}
        />
      ) : null}
      {settingsOpen && current ? (
        <SchedulingSettings
          schoolId={current}
          canMaintain={!!school?.maintainable}
          onClose={() => setSettingsOpen(false)}
          onUnauthorized={() => void onUnauthorized()}
        />
      ) : null}
      {scheduling && classId ? (
        <SchedulePlanner
          classId={classId}
          onClose={() => setScheduling(false)}
          onScheduled={() => void lessons.refetch()}
          onUnauthorized={() => void onUnauthorized()}
        />
      ) : null}
      {batchScheduling ? (
        <BatchSchedulePlanner
          classes={classes.data ?? []}
          onClose={() => setBatchScheduling(false)}
          onScheduled={() => {
            void classes.refetch();
            void lessons.refetch();
          }}
          onUnauthorized={() => void onUnauthorized()}
        />
      ) : null}
      {attendanceFor && classId ? (
        <AttendanceModal
          lesson={attendanceFor}
          members={members.data ?? []}
          onCancel={() => setAttendanceFor(null)}
          onDone={() => {
            setAttendanceFor(null);
            void lessons.refetch();
          }}
          onError={setError}
        />
      ) : null}
      {reconcileFor ? (
        <ReconciliationDrawer
          id={reconcileFor}
          onClose={() => setReconcileFor(null)}
        />
      ) : null}
    </>
  );
}

export function PublishLessonButton({
  lesson,
  onDone,
  onError,
}: {
  lesson: LessonView;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function publish() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/lessons/{id}/publish",
        {
          params: { path: { id: lesson.id! } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: lesson.version },
        },
      );
      if (response.status === 409) {
        onError("课次状态已变化，请刷新后再发布。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("发布失败，请核对教务维护权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size="small" disabled={busy} onClick={() => void publish()}>
      发布
    </Button>
  );
}

export function CloseLessonButton({
  lesson,
  onDone,
  onError,
}: {
  lesson: LessonView;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function close() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/lessons/{id}/close", {
        params: { path: { id: lesson.id! } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: lesson.version },
      });
      if (response.status === 409) {
        onError("课次状态已变化，请刷新后再关闭。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("关闭失败，请核对课次关闭权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size="small" danger disabled={busy} onClick={() => void close()}>
      关闭课次
    </Button>
  );
}

/** 考勤确认：必须覆盖全部在班报名，提交后课次进入已确认。 */
export function AttendanceModal({
  lesson,
  members,
  onCancel,
  onDone,
  onError,
}: {
  lesson: LessonView;
  members: ClassMemberView[];
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [statuses, setStatuses] = useState<Record<string, string>>(() =>
    Object.fromEntries(members.map((item) => [item.enrollmentId!, "PRESENT"])),
  );
  const [attendedMinutes, setAttendedMinutes] = useState<
    Record<string, number>
  >({});

  async function submit() {
    const duration = lessonDurationMinutes(lesson);
    if (
      members.some((member) => {
        const id = member.enrollmentId!;
        return (
          statuses[id] === "PARTIAL" &&
          (!attendedMinutes[id] ||
            attendedMinutes[id] <= 0 ||
            attendedMinutes[id] >= duration)
        );
      })
    ) {
      onError("部分缺勤请填写大于 0 且少于课次时长的实际出勤分钟。");
      return;
    }
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/lessons/{id}/attendance",
        {
          params: { path: { id: lesson.id! } },
          headers: { [token.headerName!]: token.token! },
          body: {
            expectedVersion: lesson.version,
            records: members.map((item) => ({
              enrollmentId: item.enrollmentId,
              status: statuses[item.enrollmentId!] ?? "PRESENT",
              ...(statuses[item.enrollmentId!] === "PARTIAL"
                ? { attendedMinutes: attendedMinutes[item.enrollmentId!] }
                : {}),
            })),
          },
        },
      );
      if (response.status === 409) {
        onError("课次状态或名单已变化，请刷新后重新登记。");
        return;
      }
      if (!response.ok) {
        onError("考勤确认失败，请核对考勤登记权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`考勤确认：${lesson.lessonDate}`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      <Table
        rowKey="enrollmentId"
        size="small"
        dataSource={members}
        pagination={false}
        columns={[
          { title: "学员", dataIndex: "studentName" },
          {
            title: "出勤",
            key: "status",
            render: (_: unknown, member: ClassMemberView) => {
              const id = member.enrollmentId!;
              return (
                <Space orientation="vertical" size={4}>
                  <Radio.Group
                    aria-label={`出勤-${member.studentName}`}
                    value={statuses[id]}
                    onChange={(event) =>
                      setStatuses((current) => ({
                        ...current,
                        [id]: event.target.value,
                      }))
                    }
                    options={[
                      { value: "PRESENT", label: "出勤" },
                      { value: "PARTIAL", label: "部分缺勤" },
                      { value: "ABSENT", label: "缺勤" },
                      { value: "LEAVE", label: "请假" },
                    ]}
                  />
                  {statuses[id] === "PARTIAL" ? (
                    <Space>
                      <InputNumber
                        aria-label={`实际出勤分钟-${member.studentName}`}
                        min={1}
                        max={Math.max(1, lessonDurationMinutes(lesson) - 1)}
                        precision={0}
                        value={attendedMinutes[id]}
                        onChange={(value) =>
                          setAttendedMinutes((current) => ({
                            ...current,
                            [id]: typeof value === "number" ? value : 0,
                          }))
                        }
                      />
                      <Typography.Text>分钟</Typography.Text>
                    </Space>
                  ) : null}
                </Space>
              );
            },
          },
        ]}
        locale={{ emptyText: "该班级没有在班报名" }}
      />
      <Button
        type="primary"
        style={{ marginTop: 12 }}
        onClick={() => void submit()}
        loading={busy}
        disabled={busy || members.length === 0}
      >
        确认考勤
      </Button>
    </Modal>
  );
}

export function ClassCreator({
  schoolId,
  curricula,
  onCancel,
  onCreated,
}: {
  schoolId: string;
  curricula: Curriculum[];
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/classes", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          schoolId,
          curriculumId: values.curriculumId,
          name: values.name,
          classType: values.classType ?? "NORMAL",
          groupTraining: values.groupTraining ?? false,
        },
      });
      if (!response.ok) {
        setError(
          response.status === 409
            ? "该学校已有同名班级。"
            : "班级创建失败，请核对课程归属。",
        );
        return;
      }
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新建班级"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical">
        <Form.Item
          label="班级名称"
          name="name"
          rules={[
            { required: true, whitespace: true, message: "请输入班级名称" },
          ]}
        >
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="团培班"
          name="groupTraining"
          valuePropName="checked"
          initialValue={false}
          extra="沿用旧项目的班级属性；报名、课时和账户仍按个人分别记账。"
        >
          <Switch disabled={busy} />
        </Form.Item>
        <Form.Item
          label="班型"
          name="classType"
          initialValue="NORMAL"
          extra="结算模式 0（自动）时：VIP 走平均价格，大班与普通班走课程匹配"
        >
          <Select
            disabled={busy}
            options={[
              { value: "VIP", label: "VIP" },
              { value: "LARGE", label: "大班" },
              { value: "NORMAL", label: "普通班" },
            ]}
          />
        </Form.Item>
        <Form.Item
          label="课程"
          name="curriculumId"
          rules={[{ required: true, message: "请选择课程" }]}
        >
          <Select
            options={curricula.map((item) => ({
              value: item.id,
              label: item.name,
            }))}
            disabled={busy}
            notFoundContent="该学校还没有课程"
          />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          创建班级
        </Button>
      </Form>
    </Modal>
  );
}

export function LessonCreator({
  classId,
  onCancel,
  onCreated,
}: {
  classId: string;
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/teaching/lessons", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          classId,
          lessonDate: values.lessonDate,
          startsAt: values.startsAt,
          endsAt: values.endsAt,
          teacherName: values.teacherName || undefined,
          roomName: values.roomName || undefined,
          topic: values.topic || undefined,
          hours: String(values.hours),
        },
      });
      if (!response.ok) {
        setError("课次创建失败，请核对日期、时间与课时。");
        return;
      }
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新建课次"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical" initialValues={{ hours: 1 }}>
        <Form.Item
          label="上课日期"
          name="lessonDate"
          rules={[{ required: true, message: "请输入日期" }]}
        >
          <Input placeholder="YYYY-MM-DD" maxLength={10} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="开始时间"
          name="startsAt"
          rules={[{ required: true, message: "请输入开始时间" }]}
        >
          <Input placeholder="09:00" maxLength={5} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="结束时间"
          name="endsAt"
          rules={[{ required: true, message: "请输入结束时间" }]}
        >
          <Input placeholder="10:00" maxLength={5} disabled={busy} />
        </Form.Item>
        <Form.Item label="教师" name="teacherName">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="教室" name="roomName">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="课题" name="topic">
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="授课课时"
          name="hours"
          rules={[{ required: true, message: "请输入课时" }]}
        >
          <InputNumber
            min={0.01}
            max={99}
            step={0.5}
            precision={2}
            style={{ width: "100%" }}
            disabled={busy}
          />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          保存草稿
        </Button>
      </Form>
    </Modal>
  );
}

/** 单条报名的课时对账：购买、已消耗、剩余。 */
export function ReconciliationDrawer({
  id,
  onClose,
}: {
  id: string;
  onClose: () => void;
}) {
  const reconciliation = useQuery({
    queryKey: ["reconciliation", id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/enrollments/{id}/reconciliation",
        {
          params: { path: { id } },
          signal,
        },
      );
      if (!response.ok || !data) throw new Error("无法读取课时对账");
      return data;
    },
  });

  return (
    <Drawer title="课时对账" open size="large" onClose={onClose}>
      {reconciliation.error ? (
        <Alert title={reconciliation.error.message} type="error" />
      ) : null}
      {reconciliation.data ? (
        <>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="报名状态">
              {reconciliation.data.status}
            </Descriptions.Item>
            <Descriptions.Item label="结算模式">
              {reconciliation.data.settlementMode}
            </Descriptions.Item>
            <Descriptions.Item label="购买课时">
              {reconciliation.data.hours}
            </Descriptions.Item>
            <Descriptions.Item label="已消耗课时">
              {reconciliation.data.consumedHours}
            </Descriptions.Item>
            <Descriptions.Item label="剩余课时">
              {reconciliation.data.remainingHours}
            </Descriptions.Item>
            <Descriptions.Item label="已消耗金额">
              {reconciliation.data.consumedAmount}
            </Descriptions.Item>
          </Descriptions>
          <Table
            rowKey="id"
            size="small"
            style={{ marginTop: 12 }}
            dataSource={reconciliation.data.consumptions ?? []}
            pagination={false}
            columns={[
              { title: "课次日期", dataIndex: "lessonDate" },
              { title: "课时", dataIndex: "hours" },
              { title: "金额", dataIndex: "amount" },
              { title: "单价快照", dataIndex: "priceSnapshot" },
            ]}
            locale={{ emptyText: "还没有消费记录" }}
          />
        </>
      ) : null}
    </Drawer>
  );
}
