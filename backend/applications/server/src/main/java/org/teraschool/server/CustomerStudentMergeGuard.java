package org.teraschool.server;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentMergeGuard;

/** 应用组合层对客户转学员关联的保护；不搬迁招生来源关系。 */
@Service
class CustomerStudentMergeGuard implements StudentMergeGuard {
  private final DSLContext sql;

  CustomerStudentMergeGuard(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public String blocker(UUID studentId) {
    return sql.fetchExists(
            sql.selectOne()
                .from("admissions.customer_student_link")
                .where("student_id=?", studentId))
        ? "CUSTOMER_SOURCE_LINK"
        : null;
  }
}
