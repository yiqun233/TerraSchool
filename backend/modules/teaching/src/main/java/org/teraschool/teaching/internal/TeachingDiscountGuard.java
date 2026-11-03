package org.teraschool.teaching.internal;

import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.teraschool.enrollment.api.DiscountGuard;
import org.teraschool.enrollment.api.EnrollmentConflict;

/** 消费发生后禁止直接改价；历史调整须等待版本化调整单，不能静默改后续计价。 */
@Component
public class TeachingDiscountGuard implements DiscountGuard {
  private final DSLContext sql;

  public TeachingDiscountGuard(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public void requireAllowed(
      UUID enrollmentId, UUID schoolId, UUID studentId, BigDecimal newNetAmount) {
    if (sql.fetchExists(
        sql.selectOne().from("teaching.consumption").where("enrollment_id=?", enrollmentId)))
      throw new EnrollmentConflict("DISCOUNT_AFTER_CONSUMPTION");
  }
}
