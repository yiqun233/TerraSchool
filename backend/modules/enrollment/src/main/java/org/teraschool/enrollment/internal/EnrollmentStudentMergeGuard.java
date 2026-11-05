package org.teraschool.enrollment.internal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentMergeGuard;

/** 报名域只读阻止检查，避免合并时迁移或重算报名及课时历史。 */
@Service
class EnrollmentStudentMergeGuard implements StudentMergeGuard {
  private final DSLContext sql;

  EnrollmentStudentMergeGuard(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public String blocker(UUID studentId) {
    return sql.fetchExists(
            sql.selectOne().from("enrollment.enrollment").where("student_id=?", studentId))
        ? "ENROLLMENT_HISTORY"
        : null;
  }
}
