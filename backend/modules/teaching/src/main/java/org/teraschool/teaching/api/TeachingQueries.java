package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

public interface TeachingQueries {
  TeachingCapabilities capabilities();

  List<ClassView> classes(UUID schoolId);

  /** 当前在班报名，供考勤登记选择。 */
  List<ClassMemberView> members(UUID classId);

  List<LessonView> lessons(UUID classId);

  /** 课次 CSV 导出（学校 + 日期范围），导出写审计。 */
  String exportLessonsCsv(UUID schoolId, String from, String to);

  List<AttendanceInput> attendance(UUID lessonId);

  EnrollmentReconciliation reconciliation(UUID enrollmentId);
}
