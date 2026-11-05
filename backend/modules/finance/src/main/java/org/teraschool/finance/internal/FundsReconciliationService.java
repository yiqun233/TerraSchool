package org.teraschool.finance.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.finance.api.FinancialReportQueries;
import org.teraschool.finance.api.FundsReconciliation;
import org.teraschool.finance.api.ReceiptDailyTotal;
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

  @Override
  public List<ReceiptDailyTotal> receiptDailyTotals(UUID schoolId, String from, String to) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var start = reportDate(from, "起始日期");
    var end = reportDate(to, "结束日期");
    if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于起始日期");
    if (ChronoUnit.DAYS.between(start, end) > 366)
      throw new IllegalArgumentException("收款统计日期范围不能超过367天");
    var permission = actors.requireAction("FUNDS_READ");
    if (!permission.schools().contains(schoolId)) throw new AccessDeniedException("无该学校资金报表权限");
    return tx.execute(
        permission.tenantId(),
        () -> {
          schools.lockTenant(permission.tenantId());
          var verified = actors.requireActionInTransaction("FUNDS_READ");
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校资金报表权限");
          return sql.fetch(
                  "select occurred_on,method,status,count(*) as receipt_count,"
                      + "coalesce(sum(amount),0) as gross_amount from funds.receipt "
                      + "where school_id=? and occurred_on between ? and ? "
                      + "group by occurred_on,method,status order by occurred_on,method,status",
                  schoolId,
                  start,
                  end)
              .map(
                  row ->
                      new ReceiptDailyTotal(
                          row.get("occurred_on", LocalDate.class).toString(),
                          row.get("method", String.class),
                          row.get("status", String.class),
                          row.get("receipt_count", Long.class),
                          money(row.get("gross_amount", BigDecimal.class))));
        });
  }

  @Override
  public org.teraschool.finance.api.DailyOverview dailyOverview(UUID schoolId, String dayText) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var day = reportDate(dayText, "统计日期");
    var enrollmentScope = actors.requireResourceScope("ENROLLMENT");
    var fundsActor = actors.requireAction("FUNDS_READ");
    var teachingScope = actors.requireResourceScope("TEACHING");
    if (!enrollmentScope.tenantId().equals(fundsActor.tenantId())
        || !enrollmentScope.tenantId().equals(teachingScope.tenantId())
        || !enrollmentScope.readableSchoolIds().contains(schoolId)
        || !fundsActor.schools().contains(schoolId)
        || !teachingScope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("需要报名、资金和教务的学校读取权限");
    return tx.execute(
        enrollmentScope.tenantId(),
        () -> {
          schools.lockTenant(enrollmentScope.tenantId());
          var verifiedEnrollment = actors.requireResourceScopeInTransaction("ENROLLMENT");
          var verifiedFunds = actors.requireActionInTransaction("FUNDS_READ");
          var verifiedTeaching = actors.requireResourceScopeInTransaction("TEACHING");
          if (!verifiedEnrollment.readableSchoolIds().contains(schoolId)
              || !verifiedFunds.schools().contains(schoolId)
              || !verifiedTeaching.readableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("需要报名、资金和教务的学校读取权限");
          var row =
              sql.fetchOne(
                  "select"
                      + " (select count(*) from enrollment.enrollment e where e.school_id=? and e.created_at::date=?) as enrollment_count,"
                      + " (select count(*) from funds.receipt r where r.school_id=? and r.occurred_on=? and r.status='CONFIRMED') as confirmed_receipt_count,"
                      + " (select coalesce(sum(r.amount),0) from funds.receipt r where r.school_id=? and r.occurred_on=? and r.status='CONFIRMED') as confirmed_receipt_amount,"
                      + " (select count(*) from funds.receipt r where r.school_id=? and r.occurred_on=? and r.status='VOIDED') as voided_receipt_count,"
                      + " (select coalesce(sum(r.amount),0) from funds.receipt r where r.school_id=? and r.occurred_on=? and r.status='VOIDED') as voided_receipt_amount,"
                      + " (select count(*) from teaching.lesson l join teaching.class c on c.id=l.class_id and c.tenant_id=l.tenant_id where c.school_id=? and l.lesson_date=?) as lesson_count,"
                      + " (select count(*) from teaching.lesson l join teaching.class c on c.id=l.class_id and c.tenant_id=l.tenant_id where c.school_id=? and l.lesson_date=? and l.status='CLOSED') as closed_lesson_count,"
                      + " (select count(*) from teaching.attendance a join teaching.lesson l on l.id=a.lesson_id and l.tenant_id=a.tenant_id join teaching.class c on c.id=l.class_id and c.tenant_id=l.tenant_id where c.school_id=? and l.lesson_date=?) as attendance_record_count",
                  schoolId,
                  day,
                  schoolId,
                  day,
                  schoolId,
                  day,
                  schoolId,
                  day,
                  schoolId,
                  day,
                  schoolId,
                  day,
                  schoolId,
                  day,
                  schoolId,
                  day);
          return new org.teraschool.finance.api.DailyOverview(
              day.toString(),
              row.get("enrollment_count", Long.class),
              row.get("confirmed_receipt_count", Long.class),
              money(row.get("confirmed_receipt_amount", BigDecimal.class)),
              row.get("voided_receipt_count", Long.class),
              money(row.get("voided_receipt_amount", BigDecimal.class)),
              row.get("lesson_count", Long.class),
              row.get("closed_lesson_count", Long.class),
              row.get("attendance_record_count", Long.class));
        });
  }

  private LocalDate reportDate(String value, String label) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException | NullPointerException error) {
      throw new IllegalArgumentException(label + "格式无效，应为 YYYY-MM-DD");
    }
  }

  private String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }
}
