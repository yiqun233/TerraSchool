package org.teraschool.funds.internal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentMergeGuard;

/** 资金域只读阻止检查，避免合并时改写账户、收款或退款流水。 */
@Service
class FundsStudentMergeGuard implements StudentMergeGuard {
  private final DSLContext sql;

  FundsStudentMergeGuard(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public String blocker(UUID studentId) {
    if (sql.fetchExists(
        sql.selectOne().from("funds.account_entry").where("student_id=?", studentId)))
      return "FUNDS_HISTORY";
    if (sql.fetchExists(sql.selectOne().from("funds.receipt").where("student_id=?", studentId)))
      return "RECEIPT_HISTORY";
    return null;
  }
}
