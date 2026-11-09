import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import {
  Page,
  PageHeader,
  PageState,
  StatusTag,
  Toolbar,
} from "../../shared/ui/Page";
import { PersistentCsvExportButton } from "../shared/PersistentCsvExportButton";
import { SchedulePlanner } from "./SchedulePlanner";
import { BatchSchedulePlanner } from "./BatchSchedulePlanner";
import { SchedulingSettings } from "./SchedulingSettings";
import {
  AttendanceModal,
  ClassCapacityEditor,
  ClassCreator,
  CloseLessonButton,
  LessonCreator,
  MakeupLessonModal,
  PublishLessonButton,
  ReconciliationDrawer,
  RescheduleLessonModal,
  SwapLessonModal,
} from "./TeachingDialogs";
import { LESSON_STATUS, TeacherWorkbenchPanel } from "./TeachingPanels";
import {
  AttendanceReportPanel,
  ResourceDailyPanel,
  TeachingOverviewPanel,
} from "./TeachingReportPanels";
import type { components } from "../../shared/generated/api";
import type {
  LessonView,
  ClassMemberView,
  EnrollmentView,
  TeacherWorkbench,
  TeacherClassWorkbench,
  TeacherWorkbenchLesson,
  DailyResourceSchedule,
  StudentConsumptionPage,
  AttendanceReport,
  TeachingOverviewReport,
} from "./teachingTypes";

// 原有导入路径保持不变：外部（含测试）仍可从本文件取这些组件。
export {
  AttendanceModal,
  ClassCapacityEditor,
  ClassCreator,
  CloseLessonButton,
  LessonCreator,
  MakeupLessonModal,
  PublishLessonButton,
  ReconciliationDrawer,
  RescheduleLessonModal,
  SwapLessonModal,
} from "./TeachingDialogs";

function localDate(offsetDays: number) {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
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
  const [transferTargetClassId, setTransferTargetClassId] = useState<string>();
  const [transferApprovalId, setTransferApprovalId] = useState("");
  const [addingMembersOpen, setAddingMembersOpen] = useState(false);
  const [memberEnrollmentId, setMemberEnrollmentId] = useState<string>();
  const [memberClassIds, setMemberClassIds] = useState<string[]>([]);
  const [enrollmentPage, setEnrollmentPage] = useState(0);
  const [addingMemberBusy, setAddingMemberBusy] = useState(false);
  const [creatingClass, setCreatingClass] = useState(false);
  const [capacityFor, setCapacityFor] = useState<
    components["schemas"]["ClassView"] | null
  >(null);
  const [creatingLesson, setCreatingLesson] = useState(false);
  const [scheduling, setScheduling] = useState(false);
  const [batchScheduling, setBatchScheduling] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [attendanceFor, setAttendanceFor] = useState<LessonView | null>(null);
  const [rescheduleFor, setRescheduleFor] = useState<LessonView | null>(null);
  const [swapFor, setSwapFor] = useState<LessonView | null>(null);
  const [makeupFor, setMakeupFor] = useState<LessonView | null>(null);
  const [reconcileFor, setReconcileFor] = useState<string | null>(null);
  const [teacherClassId, setTeacherClassId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [workbenchFrom, setWorkbenchFrom] = useState(() => localDate(0));
  const [workbenchTo, setWorkbenchTo] = useState(() => localDate(30));
  const [resourceDay, setResourceDay] = useState(() => localDate(0));
  const [attendanceFrom, setAttendanceFrom] = useState(() => localDate(-30));
  const [attendanceTo, setAttendanceTo] = useState(() => localDate(0));
  const [overviewOpen, setOverviewOpen] = useState(false);
  const [resourcePage, setResourcePage] = useState(1);
  const [consumptionPage, setConsumptionPage] = useState(1);
  const cancelRequests = useRef(
    new Map<string, { reason: string; version: number; requestId: string }>(),
  );
  const transferRequests = useRef(
    new Map<string, { fingerprint: string; requestId: string }>(),
  );
  const withdrawalRequests = useRef(
    new Map<string, { fingerprint: string; requestId: string }>(),
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
  const selectedClass = classes.data?.find((item) => item.id === classId);
  const transferTargets =
    classes.data?.filter(
      (item) =>
        item.id !== classId &&
        item.status === "ACTIVE" &&
        item.curriculumId === selectedClass?.curriculumId,
    ) ?? [];
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
  const enrollments = useQuery({
    queryKey: ["teaching-add-member-enrollments", current, enrollmentPage],
    enabled: addingMembersOpen && !!current && !!selectedClass?.id,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/enrollment/enrollments", {
        params: {
          query: {
            schoolId: current!,
            status: "ACTIVE",
            page: enrollmentPage,
            size: 100,
          },
        },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (response.status === 403)
        throw new Error(
          "需要报名读取权限才能选择已有报名；请联系管理员开通 ENROLLMENT 读取权限。",
        );
      if (!response.ok || !data) throw new Error("无法读取有效报名列表");
      return data;
    },
  });
  const joinedEnrollmentIds = new Set(
    (members.data ?? []).map((item) => item.enrollmentId).filter(Boolean),
  );
  const availableEnrollments = (enrollments.data?.items ?? []).filter(
    (item) => item.id && !joinedEnrollmentIds.has(item.id),
  );
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
  const studentConsumption = useQuery<StudentConsumptionPage | null>({
    queryKey: ["teaching-student-consumption", current, consumptionPage],
    enabled: !!current && !!capabilities.data?.canRead,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/student-consumption",
        {
          params: {
            query: { schoolId: current!, page: consumptionPage - 1, size: 50 },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (response.status === 403) return null;
      if (!response.ok || !data) throw new Error("无法读取学员消费对账");
      return data;
    },
  });
  const attendanceReport = useQuery({
    queryKey: [
      "teaching-attendance-report",
      current,
      attendanceFrom,
      attendanceTo,
    ],
    enabled: !!current && !!capabilities.data?.canRead,
    queryFn: async ({ signal }): Promise<AttendanceReport> => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/attendance",
        {
          params: {
            query: {
              schoolId: current!,
              from: attendanceFrom,
              to: attendanceTo,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取考勤统计");
      return data;
    },
  });
  const teachingOverview = useQuery({
    queryKey: [
      "teaching-overview-report",
      current,
      attendanceFrom,
      attendanceTo,
    ],
    enabled: overviewOpen && !!current && !!capabilities.data?.canRead,
    queryFn: async ({ signal }): Promise<TeachingOverviewReport> => {
      const { data, response } = await api.GET(
        "/api/teaching/reports/overview",
        {
          params: {
            query: {
              schoolId: current!,
              from: attendanceFrom,
              to: attendanceTo,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取教务综合情况");
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

  async function transferClassMember(enrollmentId: string) {
    if (!classId || !transferTargetClassId) return;
    const reason = window.prompt("请输入转班原因")?.trim();
    if (!reason) return;
    const fingerprint = `${classId}|${transferTargetClassId}|${transferApprovalId.trim()}|${reason}`;
    const previous = transferRequests.current.get(enrollmentId);
    const requestId =
      previous?.fingerprint === fingerprint
        ? previous.requestId
        : crypto.randomUUID();
    transferRequests.current.set(enrollmentId, { fingerprint, requestId });
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/enrollments/{id}/transfer",
        {
          params: { path: { id: enrollmentId } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId,
            expectedFromClassId: classId,
            toClassId: transferTargetClassId,
            approvalId: transferApprovalId.trim() || undefined,
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
          "转班失败：请核对目标班状态、同课条件和已批准的转班审批单。原请求可重试。",
        );
        return;
      }
      transferRequests.current.delete(enrollmentId);
      setTransferTargetClassId(undefined);
      setTransferApprovalId("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["teaching-members"] }),
        queryClient.invalidateQueries({ queryKey: ["teaching-classes"] }),
      ]);
    } catch {
      setError("转班结果暂时未知，请以相同参数重试或刷新核对班级名册。");
    }
  }

  async function withdrawClassMember(enrollmentId: string) {
    if (!classId) return;
    const approvalId = window.prompt("请输入已批准的退班审批单号")?.trim();
    if (!approvalId) return;
    const reason = window.prompt("请输入退班原因")?.trim();
    if (!reason) return;
    const fingerprint = `${classId}|${approvalId}|${reason}`;
    const previous = withdrawalRequests.current.get(enrollmentId);
    const requestId =
      previous?.fingerprint === fingerprint
        ? previous.requestId
        : crypto.randomUUID();
    withdrawalRequests.current.set(enrollmentId, { fingerprint, requestId });
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/teaching/enrollments/{id}/withdraw",
        {
          params: { path: { id: enrollmentId } },
          headers: { [token.headerName!]: token.token! },
          body: { requestId, classId, approvalId, reason },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setError(
          "退班失败：请核对班级、在班关系和已批准的退班审批单。原请求可重试。",
        );
        return;
      }
      withdrawalRequests.current.delete(enrollmentId);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["teaching-members"] }),
        queryClient.invalidateQueries({ queryKey: ["teaching-classes"] }),
      ]);
    } catch {
      setError("退班结果暂时未知，请使用相同参数重试或刷新名册核对。");
    }
  }

  async function addClassMember() {
    if (!memberClassIds.length || !memberEnrollmentId || addingMemberBusy)
      return;
    setAddingMemberBusy(true);
    setError("");
    try {
      const token = await csrf();
      const response = await fetch("/api/teaching/classes/members/batch", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          [token.headerName!]: token.token!,
        },
        body: JSON.stringify({
          enrollmentId: memberEnrollmentId,
          classIds: memberClassIds,
        }),
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setError(
          response.status === 403
            ? "加入班级失败：需要教务维护权限；同时请确认报名读取权限和审批要求。"
            : "加入班级失败：请确认报名仍有效、课程匹配、班级有可用名额且符合审批规则。",
        );
        return;
      }
      setAddingMembersOpen(false);
      setMemberEnrollmentId(undefined);
      setMemberClassIds([]);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["teaching-members"] }),
        queryClient.invalidateQueries({ queryKey: ["teaching-classes"] }),
      ]);
    } catch {
      setError(
        "加入班级结果暂时未知；刷新名册核对后可使用相同报名重试。重复加入由服务端按报名和班级幂等处理。",
      );
    } finally {
      setAddingMemberBusy(false);
    }
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
    return (
      <PageState
        title="教务与考勤"
        description="正在确认当前账号的教务权限。"
        stateTitle="无法读取教务权限"
        stateHint={capabilities.error.message}
        action={
          <Button onClick={() => void capabilities.refetch()}>重试</Button>
        }
      />
    );
  if (capabilities.isPending)
    return (
      <PageState
        title="教务与考勤"
        description="正在确认当前账号的教务权限。"
        loading
      />
    );
  if (!capabilities.data?.canRead)
    return (
      <PageState
        title="教务与考勤"
        stateTitle="没有教务读取权限"
        stateHint="需要 TEACHING_READ 权限；请联系机构管理员在“账号与权限”中开通。"
      />
    );
  const schools = capabilities.data.schools ?? [];
  if (schools.length === 0)
    return (
      <PageState
        title="教务与考勤"
        stateTitle="当前范围内没有可查看的学校"
        stateHint="请先建校并给账号分配学校范围，班级与课次才会可用。"
      />
    );

  return (
    <Page>
      <PageHeader
        title="教务与考勤"
        description="班级、课次、考勤与消费在这里完成。关闭课次才固化消费；草稿与发布不产生课时消耗。"
        actions={
          <>
            {school?.maintainable ? (
              <>
                <Button type="primary" onClick={() => setCreatingClass(true)}>
                  新建班级
                </Button>
                <Button onClick={() => setBatchScheduling(true)}>
                  多班自动排课
                </Button>
              </>
            ) : null}
            <Button onClick={() => setSettingsOpen(true)}>排课设置</Button>
            <Button
              onClick={() => setOverviewOpen(true)}
              loading={teachingOverview.isFetching}
            >
              {overviewOpen ? "刷新教务汇总" : "加载教务汇总"}
            </Button>
            <Button
              onClick={() => void classes.refetch()}
              loading={classes.isFetching}
            >
              刷新
            </Button>
          </>
        }
      />
      <Toolbar>
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
        <Typography.Text type="secondary">
          关闭课次才固化消费；草稿与发布不产生课时消耗
        </Typography.Text>
      </Toolbar>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <TeacherWorkbenchPanel
        from={workbenchFrom}
        to={workbenchTo}
        onFromChange={setWorkbenchFrom}
        onToChange={setWorkbenchTo}
        data={teacherWorkbench.data}
        error={teacherWorkbench.error}
        fetching={teacherWorkbench.isFetching}
        onRefresh={() => void teacherWorkbench.refetch()}
        onOpenClass={setTeacherClassId}
      />
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
      <Card
        title="学员消费与课时对账"
        size="small"
        loading={studentConsumption.isFetching}
        style={{ marginBottom: 12 }}
      >
        <Typography.Text type="secondary">
          按报名列示累计购买、已消费、已退与剩余课时；金额为实际已固化消费金额。需要同时具备教务、报名与资金读取权限。
        </Typography.Text>
        {studentConsumption.error ? (
          <Alert
            type="error"
            title={studentConsumption.error.message}
            showIcon
            style={{ marginTop: 12 }}
          />
        ) : studentConsumption.data === null ? (
          <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
            当前账号没有该校学员消费对账权限
          </Typography.Paragraph>
        ) : (
          <Table
            style={{ marginTop: 12 }}
            size="small"
            rowKey="enrollmentId"
            dataSource={studentConsumption.data?.items ?? []}
            pagination={{
              current: consumptionPage,
              pageSize: 50,
              total: studentConsumption.data?.total ?? 0,
              showTotal: (total) => `共 ${total} 条报名`,
              onChange: (page) => setConsumptionPage(page),
            }}
            columns={[
              { title: "学员", dataIndex: "studentName" },
              { title: "报名状态", dataIndex: "enrollmentStatus" },
              { title: "结算模式", dataIndex: "settlementMode" },
              { title: "原始课程价", dataIndex: "price" },
              { title: "购买课时", dataIndex: "purchasedHours" },
              { title: "已消费课时", dataIndex: "consumedHours" },
              { title: "已消费金额", dataIndex: "consumedAmount" },
              { title: "已退课时", dataIndex: "cancelledHours" },
              { title: "剩余课时", dataIndex: "remainingHours" },
            ]}
          />
        )}
      </Card>
      <ResourceDailyPanel
        day={resourceDay}
        onDayChange={(value) => {
          setResourceDay(value);
          setResourcePage(1);
        }}
        data={resourceSchedule.data}
        error={resourceSchedule.error}
        fetching={resourceSchedule.isFetching}
        onRefresh={() => void resourceSchedule.refetch()}
        page={resourcePage}
        onPageChange={setResourcePage}
      />
      {overviewOpen ? (
        <TeachingOverviewPanel
          data={teachingOverview.data}
          error={teachingOverview.error}
        />
      ) : null}
      <AttendanceReportPanel
        from={attendanceFrom}
        to={attendanceTo}
        onFromChange={setAttendanceFrom}
        onToChange={setAttendanceTo}
        data={attendanceReport.data}
        error={attendanceReport.error}
        fetching={attendanceReport.isFetching}
      />
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
            tabIndex: 0,
            className: "ts-row-clickable",
            "aria-label": `打开${row.name ?? "班级"}的课次`,
            onKeyDown: (event) => {
              if (event.target !== event.currentTarget) return;
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                setClassId(row.id ?? undefined);
              }
            },
          })}
          columns={[
            { title: "班级", dataIndex: "name" },
            { title: "课程", dataIndex: "curriculumName" },
            {
              title: "名额上限",
              dataIndex: "capacity",
              render: (capacity: number | undefined) =>
                capacity == null ? "不限" : capacity,
            },
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
                  <StatusTag tone="ok">进行中</StatusTag>
                ) : status === "CANCELLED" ? (
                  <StatusTag tone="neutral">已取消</StatusTag>
                ) : (
                  <StatusTag tone="info">已结班</StatusTag>
                ),
            },
            {
              title: "操作",
              render: (_: unknown, row: components["schemas"]["ClassView"]) =>
                school?.maintainable && row.status === "ACTIVE" ? (
                  <Space>
                    <Button
                      size="small"
                      onClick={(event) => {
                        event.stopPropagation();
                        setCapacityFor(row);
                      }}
                    >
                      调整名额
                    </Button>
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
            <PersistentCsvExportButton
              exportType="LESSONS"
              schoolId={current}
            />
            <Typography.Text type="secondary">
              在班 {members.data?.length ?? 0} 人
            </Typography.Text>
            {school?.maintainable ? (
              <>
                <Button
                  onClick={() => {
                    setEnrollmentPage(0);
                    setMemberEnrollmentId(undefined);
                    setMemberClassIds(classId ? [classId] : []);
                    setAddingMembersOpen(true);
                  }}
                >
                  加入已有报名
                </Button>
                <select
                  aria-label="转入目标班级"
                  className="ant-input"
                  value={transferTargetClassId ?? ""}
                  onChange={(event) =>
                    setTransferTargetClassId(event.target.value || undefined)
                  }
                  style={{ width: 220 }}
                >
                  <option value="">选择同课程目标班</option>
                  {transferTargets.map((item) => (
                    <option key={item.id} value={item.id!}>
                      {item.name}
                    </option>
                  ))}
                </select>
                <Input
                  aria-label="已批准转班审批单号"
                  placeholder="审批策略要求时填写审批单号"
                  value={transferApprovalId}
                  onChange={(event) =>
                    setTransferApprovalId(event.target.value)
                  }
                  style={{ width: 260 }}
                />
              </>
            ) : null}
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
                      <>
                        <Button
                          size="small"
                          onClick={() => setRescheduleFor(row)}
                        >
                          调课
                        </Button>
                        <Button size="small" onClick={() => setSwapFor(row)}>
                          换课
                        </Button>
                        <Button
                          size="small"
                          onClick={() => setAttendanceFor(row)}
                        >
                          考勤确认
                        </Button>
                      </>
                    ) : null}
                    {row.status === "CONFIRMED" && school?.canCloseLesson ? (
                      <CloseLessonButton
                        lesson={row}
                        onDone={() => void lessons.refetch()}
                        onError={setError}
                      />
                    ) : null}
                    {row.status === "CLOSED" && school?.maintainable ? (
                      <Button size="small" onClick={() => setMakeupFor(row)}>
                        安排补课
                      </Button>
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
                  <Space>
                    <Button
                      size="small"
                      onClick={() =>
                        setReconcileFor(member.enrollmentId ?? null)
                      }
                    >
                      课时对账
                    </Button>
                    <Button
                      size="small"
                      disabled={
                        !school?.maintainable ||
                        !transferTargetClassId ||
                        !member.enrollmentId
                      }
                      onClick={() =>
                        void transferClassMember(member.enrollmentId!)
                      }
                    >
                      转班
                    </Button>
                    <Button
                      size="small"
                      danger
                      disabled={!school?.maintainable || !member.enrollmentId}
                      onClick={() =>
                        void withdrawClassMember(member.enrollmentId!)
                      }
                    >
                      退班
                    </Button>
                  </Space>
                ),
              },
            ]}
            locale={{ emptyText: "该班级还没有报名" }}
          />
        </Card>
      ) : null}
      <Modal
        title={`加入已有报名${selectedClass?.name ? ` · ${selectedClass.name}` : ""}`}
        open={addingMembersOpen}
        onCancel={() => setAddingMembersOpen(false)}
        onOk={() => void addClassMember()}
        okText="加入班级"
        cancelText="取消"
        okButtonProps={{
          disabled:
            !memberEnrollmentId ||
            !memberClassIds.length ||
            !school?.maintainable ||
            addingMemberBusy,
        }}
        confirmLoading={addingMemberBusy}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          选择本校有效报名加入当前班级。现有规则与审批要求仍以服务端校验为准。
        </Typography.Paragraph>
        {enrollments.error ? (
          <Alert
            type="error"
            showIcon
            message={enrollments.error.message}
            style={{ marginBottom: 12 }}
          />
        ) : null}
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="搜索并选择有效报名"
          loading={enrollments.isLoading}
          value={memberEnrollmentId}
          onChange={setMemberEnrollmentId}
          options={availableEnrollments.map((item: EnrollmentView) => ({
            value: item.id!,
            label: `${item.studentName ?? "未命名学员"} · ${item.productName ?? "产品未命名"} · ${item.curriculumName ?? "课程未命名"}`,
          }))}
          style={{ width: "100%" }}
          notFoundContent={
            enrollments.isLoading ? "正在读取…" : "本页没有可加入的有效报名"
          }
        />
        <Typography.Paragraph style={{ marginTop: 16, marginBottom: 6 }}>
          目标班级（可多选）
        </Typography.Paragraph>
        <Select
          mode="multiple"
          aria-label="目标班级"
          placeholder="选择本校进行中的课程班级"
          value={memberClassIds}
          onChange={setMemberClassIds}
          options={(classes.data ?? [])
            .filter((item) => item.status === "ACTIVE")
            .map((item) => ({
              value: item.id!,
              label: `${item.name} · ${item.curriculumName} · ${item.memberCount ?? 0}/${item.capacity ?? "不限"}${item.capacity != null && (item.memberCount ?? 0) >= item.capacity ? "（已满）" : ""}`,
            }))}
          style={{ width: "100%" }}
        />
        <Typography.Text type="secondary">
          服务端会校验报名课程与班级课程匹配和名额；容量未设置视为不限，满班不能新增，暂不支持候补。
        </Typography.Text>
        <Space style={{ marginTop: 12 }}>
          <Button
            size="small"
            disabled={enrollmentPage === 0}
            onClick={() => setEnrollmentPage((page) => Math.max(0, page - 1))}
          >
            上一页
          </Button>
          <Typography.Text type="secondary">
            第 {enrollmentPage + 1} 页 · 共 {enrollments.data?.total ?? 0} 条
          </Typography.Text>
          <Button
            size="small"
            disabled={
              (enrollmentPage + 1) * 100 >= (enrollments.data?.total ?? 0)
            }
            onClick={() => setEnrollmentPage((page) => page + 1)}
          >
            下一页
          </Button>
        </Space>
      </Modal>
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
      {capacityFor ? (
        <ClassCapacityEditor
          key={capacityFor.id}
          classView={capacityFor}
          onCancel={() => setCapacityFor(null)}
          onUpdated={() => {
            setCapacityFor(null);
            void classes.refetch();
          }}
          onUnauthorized={onUnauthorized}
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
      {rescheduleFor ? (
        <RescheduleLessonModal
          lesson={rescheduleFor}
          onCancel={() => setRescheduleFor(null)}
          onDone={() => {
            setRescheduleFor(null);
            void lessons.refetch();
          }}
          onError={setError}
        />
      ) : null}
      {swapFor ? (
        <SwapLessonModal
          lesson={swapFor}
          candidates={(lessons.data ?? []).filter(
            (item) =>
              item.id !== swapFor.id &&
              item.status === "PUBLISHED" &&
              item.classId === swapFor.classId,
          )}
          onCancel={() => setSwapFor(null)}
          onDone={() => {
            setSwapFor(null);
            void lessons.refetch();
          }}
          onError={setError}
        />
      ) : null}
      {makeupFor ? (
        <MakeupLessonModal
          lesson={makeupFor}
          onCancel={() => setMakeupFor(null)}
          onDone={() => {
            setMakeupFor(null);
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
    </Page>
  );
}
