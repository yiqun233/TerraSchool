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

  TeachingCapabilities capabilities();

  /** 当前账号绑定的教师在明确日期区间内的课表及所授班级。 */
  TeacherWorkbench myWorkbench(String from, String to);

  /** 当前账号绑定的教师仅能查看本人实际任教班级的名册与课次。 */
  TeacherClassWorkbench myClass(UUID classId);

  DailyResourceSchedule dailyResourceSchedule(UUID schoolId, String day, int page, int pageSize);

  /** 学校范围内逐报名的累计消费/退课/剩余课时，需同时具备报名、教务和资金读取权限。 */
  StudentConsumptionPage studentConsumption(UUID schoolId, int page, int size);

  /** 学校范围按课次和日期汇总已登记考勤；未登记课次的各状态计数为 0。 */
  AttendanceReport attendanceReport(UUID schoolId, String from, String to);

  List<ClassView> classes(UUID schoolId);

  /** 当前在班报名，供考勤登记选择。 */
  List<ClassMemberView> members(UUID classId);

  List<LessonView> lessons(UUID classId);

  /** 课次 CSV 导出（学校 + 日期范围），导出写审计。 */
  String exportLessonsCsv(UUID schoolId, String from, String to);

  List<AttendanceInput> attendance(UUID lessonId);

  EnrollmentReconciliation reconciliation(UUID enrollmentId);
}
