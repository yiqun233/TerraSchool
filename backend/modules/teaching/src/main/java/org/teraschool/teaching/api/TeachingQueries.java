package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

public interface TeachingQueries {
  TeachingCapabilities capabilities();

  List<ClassView> classes(UUID schoolId);

  /** 当前在班报名，供考勤登记选择。 */
  List<ClassMemberView> members(UUID classId);

  List<LessonView> lessons(UUID classId);

  List<AttendanceInput> attendance(UUID lessonId);

  EnrollmentReconciliation reconciliation(UUID enrollmentId);
}
