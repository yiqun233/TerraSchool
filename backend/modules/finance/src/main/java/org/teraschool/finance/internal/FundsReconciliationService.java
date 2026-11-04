package org.teraschool.finance.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.finance.api.FinancialReportQueries;
import org.teraschool.finance.api.FundsReconciliation;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.TenantTransactions;

/** 从原始资金流水与有效分配重算学校资金平衡式。 */
@Service
class FundsReconciliationService implements FinancialReportQueries {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;

  FundsReconciliationService(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolAccess schools) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
  }

  @Override
  public FundsReconciliation fundsReconciliation(UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var permission = actors.requireAction("FUNDS_READ");
    if (!permission.schools().contains(schoolId)) throw new AccessDeniedException("无该学校资金对账权限");
    return tx.execute(
        permission.tenantId(),
        () -> {
          schools.lockTenant(permission.tenantId());
          var verified = actors.requireActionInTransaction("FUNDS_READ");
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校资金对账权限");
          var row =
              sql.fetchOne(
                  "with ledger as ("
                      + "select coalesce(sum(e.amount) filter (where e.kind='OPENING'),0) as opening,"
                      + "coalesce(sum(e.amount) filter (where e.kind in ('RECEIPT','RECEIPT_VOID')),0) as receipts,"
                      + "coalesce(sum(e.amount) filter (where e.kind='CASH_REFUND'),0) as refunds,"
                      + "coalesce(sum(e.amount),0) as balance "
                      + "from archive.student s left join funds.account_entry e on e.student_id=s.id "
                      + "where s.school_id=?), allocations as ("
                      + "select coalesce(sum(a.amount),0) as amount from funds.allocation a "
                      + "join funds.receipt r on r.id=a.receipt_id "
                      + "where r.school_id=? and a.status='ACTIVE') "
                      + "select ledger.opening,ledger.receipts,ledger.refunds,ledger.balance,allocations.amount "
                      + "from ledger cross join allocations",
                  schoolId,
                  schoolId);
          var opening = row.get("opening", BigDecimal.class);
          var receipts = row.get("receipts", BigDecimal.class);
          var signedRefunds = row.get("refunds", BigDecimal.class);
          var balance = row.get("balance", BigDecimal.class);
          var allocated = row.get("amount", BigDecimal.class);
          var difference =
              opening.add(receipts).add(signedRefunds).subtract(allocated).subtract(balance);
          return new FundsReconciliation(
              schoolId,
              money(opening),
              money(receipts),
              money(signedRefunds.negate()),
              money(allocated),
              money(balance),
              money(difference));
        });
  }

  private String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }
}
