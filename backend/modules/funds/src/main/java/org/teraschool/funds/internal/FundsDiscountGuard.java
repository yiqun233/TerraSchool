package org.teraschool.funds.internal;

import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.teraschool.enrollment.api.DiscountGuard;
import org.teraschool.enrollment.api.EnrollmentConflict;

/** 减免不能让已分配金额超过净应收；资金重新分配应由明确的资金用例执行。 */
@Component
public class FundsDiscountGuard implements DiscountGuard {
  private final DSLContext sql;

  public FundsDiscountGuard(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public void requireAllowed(UUID enrollmentId, BigDecimal newNetAmount) {
    var allocated =
        sql.fetchOne(
                "select coalesce(sum(amount),0) from funds.allocation where enrollment_id=? and status='ACTIVE'",
                enrollmentId)
            .get(0, BigDecimal.class);
    if (allocated.compareTo(newNetAmount) > 0)
      throw new EnrollmentConflict("DISCOUNT_REQUIRES_ALLOCATION_RELEASE");
  }
}
