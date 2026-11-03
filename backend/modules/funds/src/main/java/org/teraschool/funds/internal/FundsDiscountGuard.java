package org.teraschool.funds.internal;

import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.teraschool.enrollment.api.DiscountGuard;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.platform.api.RequestTrace;

/** 同一事务内重建报名分配：保留原收款来源，超出新应收的部分回到账户流水。 */
@Component
public class FundsDiscountGuard implements DiscountGuard {
  private final DSLContext sql;
  private final CurrentActor actors;

  public FundsDiscountGuard(DSLContext sql, CurrentActor actors) {
    this.sql = sql;
    this.actors = actors;
  }

  @Override
  public void requireAllowed(
      UUID enrollmentId, UUID schoolId, UUID studentId, BigDecimal newNetAmount) {
    var active =
        sql.fetch(
            "select id,receipt_id,amount from funds.allocation"
                + " where enrollment_id=? and status='ACTIVE' order by created_at,id for update",
            enrollmentId);
    var allocated =
        active.map(row -> row.get("amount", BigDecimal.class)).stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (allocated.compareTo(newNetAmount) <= 0) return;

    Actor actor = actors.requireIdentityInTransaction();
    var retained = newNetAmount;
    BigDecimal released = BigDecimal.ZERO;
    for (var row : active) {
      var oldAmount = row.get("amount", BigDecimal.class);
      sql.execute(
          "update funds.allocation set status='RELEASED',released_at=CURRENT_TIMESTAMP"
              + " where id=? and status='ACTIVE'",
          row.get("id", UUID.class));
      sql.execute(
          "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
              + " values (?,?,'RELEASE',?,?)",
          actor.tenantId(),
          studentId,
          oldAmount,
          row.get("id", UUID.class));
      released = released.add(oldAmount);
      var keep = oldAmount.min(retained);
      if (keep.signum() > 0) {
        var allocationId = UUID.randomUUID();
        sql.execute(
            "insert into funds.allocation(id,tenant_id,receipt_id,enrollment_id,amount)"
                + " values (?,?,?,?,?)",
            allocationId,
            actor.tenantId(),
            row.get("receipt_id", UUID.class),
            enrollmentId,
            keep);
        sql.execute(
            "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                + " values (?,?,'ALLOCATION',?,?)",
            actor.tenantId(),
            studentId,
            keep.negate(),
            allocationId);
        retained = retained.subtract(keep);
      }
    }
    audit(actor, "ALLOCATION_RELEASED", enrollmentId, schoolId, released, "discount");
    var reallocated = allocated.min(newNetAmount);
    if (reallocated.signum() > 0)
      audit(actor, "ALLOCATION_CREATED", enrollmentId, schoolId, reallocated, "discount");
  }

  private void audit(
      Actor actor, String action, UUID resource, UUID school, BigDecimal amount, String note) {
    sql.execute(
        "insert into audit.funds_event(tenant_id,actor_id,action,resource_id,school_id,amount,changed_fields,trace_id)"
            + " values (?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        amount,
        note,
        RequestTrace.id());
  }
}
