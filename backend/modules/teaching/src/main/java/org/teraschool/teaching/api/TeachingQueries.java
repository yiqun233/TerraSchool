package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

public interface TeachingQueries {
  record TeacherClassView(
      UUID id,
      String name,
      String curriculumName,
      String status,
      boolean groupTraining,
      int lessonCount) {}

  record TeacherLessonView(
      UUID id,
      UUID classId,
      String className,
      String curriculumName,
      String lessonDate,
      String startsAt,
      String endsAt,
      String roomName,
      String topic,
      String status,
      long version) {}

  record TeacherWorkbench(List<TeacherClassView> classes, List<TeacherLessonView> lessons) {}

  record TeacherClassWorkbench(
      TeacherClassView classInfo, List<ClassMemberView> members, List<TeacherLessonView> lessons) {
    public TeacherClassWorkbench {
      members = List.copyOf(members);
      lessons = List.copyOf(lessons);
    }
  }

  record ResourceScheduleSlot(
      UUID lessonId,
      String className,
      String lessonDate,
      String startsAt,
      String endsAt,
      String teacherName,
      String roomName,
      String topic,
      String status) {}

  record DailyResourceSchedule(
      String day,
      List<ResourceScheduleSlot> lessons,
      int missingTeacher,
      int missingRoom,
      long total,
      int page,
      int pageSize) {
    public DailyResourceSchedule {
      lessons = List.copyOf(lessons);
    }
  }

  record StudentConsumptionRow(
      UUID enrollmentId,
      UUID studentId,
      String studentName,
      String enrollmentStatus,
      int settlementMode,
      String price,
      String purchasedHours,
      String consumedHours,
      String consumedAmount,
      String cancelledHours,
      String remainingHours) {}

  record StudentConsumptionPage(List<StudentConsumptionRow> items, int page, int size, long total) {
    public StudentConsumptionPage {
      items = List.copyOf(items);
    }
  }

  record ClassConsumptionRow(
      UUID classId,
      String className,
      long lessonCount,
      String consumedHours,
      String consumedAmount) {}

  record ClassConsumptionReport(String from, String to, List<ClassConsumptionRow> classes) {
    public ClassConsumptionReport {
      classes = List.copyOf(classes);
    }
  }

  record LessonAttendanceSummary(
      UUID lessonId,
      String className,
      String lessonDate,
      String startsAt,
      String endsAt,
      String lessonStatus,
      long present,
      long partial,
      long absent,
      long leave,
      long recorded) {}

  record AttendanceReport(String from, String to, List<LessonAttendanceSummary> lessons) {
    public AttendanceReport {
      lessons = List.copyOf(lessons);
    }
  }

  record TeachingOverviewReport(
      String from,
      String to,
      long activeClasses,
      long closedClasses,
      long cancelledClasses,
      long lessons,
      long draftLessons,
      long publishedLessons,
      long confirmedLessons,
      long closedLessons,
      long present,
      long partial,
      long absent,
      long leave,
      long recorded) {}

  TeachingCapabilities capabilities();

  /** 当前账号绑定的教师在明确日期区间内的课表及所授班级。 */
  TeacherWorkbench myWorkbench(String from, String to);

  /** 当前账号绑定的教师仅能查看本人实际任教班级的名册与课次。 */
  TeacherClassWorkbench myClass(UUID classId);

  DailyResourceSchedule dailyResourceSchedule(UUID schoolId, String day, int page, int pageSize);

  /** 教师/学员视角的周课表：行按 teacher|student 聚合，列由前端按日期铺。 */
  List<TimetableGroupRow> timetableRows(UUID schoolId, String from, String to, String view);

  /** 学校范围内逐报名的累计消费/退课/剩余课时，需同时具备报名、教务和资金读取权限。 */
  StudentConsumptionPage studentConsumption(UUID schoolId, int page, int size);

  /** 按课次日期和班级汇总已固化课次消费；金额是消费额，不代表现金实收或班级收入。 */
  ClassConsumptionReport classConsumptionReport(UUID schoolId, String from, String to);

  /** 学校范围按课次和日期汇总已登记考勤；未登记课次的各状态计数为 0。 */
  AttendanceReport attendanceReport(UUID schoolId, String from, String to);

  /** 当前班级状态快照及指定课次日期范围的课次/考勤状态候选汇总。 */
  TeachingOverviewReport teachingOverviewReport(UUID schoolId, String from, String to);

  List<ClassView> classes(UUID schoolId);

  /** 班级开设的课程及各自课时/价格；主课程来自 teaching.class.curriculum_id。 */
  List<ClassCurriculumView> classCurricula(UUID classId);

  /** 指定时段的可选教师，按班级所属学校与课程过滤，并标注占用原因。 */
  List<TeacherOption> teacherOptions(
      UUID classId, String day, String startsAt, String endsAt, UUID curriculumId);

  /** 指定时段的可选教室，默认限定在班级所在校区。 */
  List<RoomOption> roomOptions(UUID classId, String day, String startsAt, String endsAt);

  /** 学校级时段模板；未配置时返回空列表，课表按实际课次时间兜底。 */
  List<MinuteSpace> schoolMinuteSpaces(UUID schoolId);

  /** 一节课的冲突明细（班级/教师/教室/学员/停课）。 */
  List<LessonConflict> lessonConflicts(UUID lessonId);

  /** 拖拽可行性矩阵：一次返回目标日期范围内每天的时段可放性。 */
  MoveScheme moveScheme(UUID lessonId, MoveSchemeQuery query);

  /** 链式调课方案：最多 5 步、20 条，按步骤数从少到多。 */
  List<SwapSchemePlan> swapSchemes(UUID lessonId, SwapSchemeQuery query);

  /** 全校冲突中心：日期段内按类型筛选，服务端一次算完。 */
  List<SchoolConflict> schoolConflicts(UUID schoolId, String from, String to, String type);

  /** 收入下钻第一层：班级 × 课程的消费汇总。 */
  List<RevenueRow> revenue(UUID schoolId, String from, String to);

  /** 收入下钻第二层：某班某课程的逐课次 × 学员消费明细。 */
  List<RevenueDetail> revenueDetails(UUID schoolId, UUID classId, String from, String to);

  /** 站内信列表，按时间倒序；unreadOnly 只返回未读。 */
  List<NotificationView> notifications(UUID schoolId, boolean unreadOnly);

  /** 学校在职教师，供教师模式筛选与改派选择。 */
  List<TeacherBrief> teachers(UUID schoolId);

  /** 学校下的校区，用于班级归属与报名校区选择。 */
  List<CampusBrief> campuses(UUID schoolId);

  /** 学员进度：按报名 × 课程列出已消费课时、剩余课时与课节进度。 */
  List<StudentProgressView> studentProgress(UUID schoolId, String keyword);

  /** 当前在班报名，供考勤登记选择。 */
  List<ClassMemberView> members(UUID classId);

  List<LessonView> lessons(UUID classId);

  /** 课次 CSV 导出（学校 + 日期范围），导出写审计。 */
  String exportLessonsCsv(UUID schoolId, String from, String to);

  List<AttendanceInput> attendance(UUID lessonId);

  EnrollmentReconciliation reconciliation(UUID enrollmentId);
}
