package org.teraschool.funds.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.approval.api.ApprovalGate;
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.enrollment.api.CancelHours;
import org.teraschool.enrollment.api.EnrollmentAccess;
import org.teraschool.enrollment.api.EnrollmentCommands;
import org.teraschool.funds.api.*;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.ConsumptionAccess;

/**
 * 收款、分配与账户流水的完整用例。
 *
 * <p>记账规则：收款确认写入正流水，分配写入负流水，释放写回正流水，撤销收款写入负流水。账户余额永远由流水
 * 汇总得出，不保存可变的“余额字段”，因此不会出现两处余额不一致。所有资金动作都先锁该学员的资金序列， 避免并发分配把同一笔钱分两次。
 */
@Service
public class FundsService implements FundsQueries, FundsCommands {
  private static final java.util.regex.Pattern DECIMAL =
      java.util.regex.Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");
  private static final Set<String> METHODS = Set.of("CASH", "BANK", "OTHER");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final StudentAccess students;
  private final EnrollmentAccess enrollments;
  private final ConsumptionAccess consumption;
  private final EnrollmentCommands enrollmentCommands;
  private final ApprovalGate approvals;

  public FundsService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      StudentAccess students,
      EnrollmentAccess enrollments,
      ConsumptionAccess consumption,
      EnrollmentCommands enrollmentCommands,
      ApprovalGate approvals) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.students = students;
    this.enrollments = enrollments;
    this.consumption = consumption;
    this.enrollmentCommands = enrollmentCommands;
    this.approvals = approvals;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public FundsCapabilities capabilities() {
    var scope = actors.requireResourceScope("FUNDS");
    var receiptSchools = schoolsOf("FUNDS_RECEIPT");
    var allocateSchools = schoolsOf("FUNDS_ALLOCATE");
    var refundSchools = schoolsOf("REFUND_APPLY");
    var paySchools = schoolsOf("REFUND_PAY");
    if (scope.readableSchoolIds().isEmpty())
      return new FundsCapabilities(true, false, false, false, false, List.of());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var names = schools.names(scope.readableSchoolIds());
          var options =
              scope.readableSchoolIds().stream()
                  .filter(names::containsKey)
                  .sorted(
                      Comparator.comparing((UUID key) -> names.get(key)).thenComparing(key -> key))
                  .map(
                      key ->
                          new FundsCapabilities.FundsSchoolOption(
                              key,
                              names.get(key),
                              receiptSchools.contains(key),
                              allocateSchools.contains(key),
                              refundSchools.contains(key),
                              paySchools.contains(key)))
                  .toList();
          return new FundsCapabilities(
              true,
              options.stream().anyMatch(FundsCapabilities.FundsSchoolOption::canReceipt),
              options.stream().anyMatch(FundsCapabilities.FundsSchoolOption::canAllocate),
              options.stream().anyMatch(FundsCapabilities.FundsSchoolOption::canRefund),
              options.stream().anyMatch(FundsCapabilities.FundsSchoolOption::canPay),
              options);
        });
  }

  @Override
  public StudentBalance balance(UUID studentId) {
    var scope = actors.requireResourceScope("FUNDS");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var student = students.require(studentId);
          if (!scope.readableSchoolIds().contains(student.schoolId()))
            throw new AccessDeniedException("无该学员的资金读取权限");
          var allocated = activeAllocated(studentId);
          var total = accountTotal(studentId);
          var reserved = reservedTotal(studentId);
          return new StudentBalance(
              studentId,
              student.name(),
              money(total),
              money(reserved),
              money(allocated),
              money(total.subtract(reserved)));
        });
  }

  @Override
  public List<Receipt> receipts(UUID studentId) {
    var scope = actors.requireResourceScope("FUNDS");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var student = students.require(studentId);
          if (!scope.readableSchoolIds().contains(student.schoolId()))
            throw new AccessDeniedException("无该学员的资金读取权限");
          return sql.fetch(
                  "select r.id,r.school_id,r.student_id,r.amount,r.method,r.occurred_on,r.status,"
                      + "r.version,(select coalesce(sum(a.amount),0) from funds.allocation a"
                      + " where a.receipt_id=r.id and a.status='ACTIVE') as allocated"
                      + " from funds.receipt r where r.student_id=? order by r.occurred_on desc, r.id"
                      + " limit 200",
                  studentId)
              .map(
                  row -> {
                    var amount = row.get("amount", BigDecimal.class);
                    var allocated = row.get("allocated", BigDecimal.class);
                    return new Receipt(
                        row.get("id", UUID.class),
                        row.get("school_id", UUID.class),
                        row.get("student_id", UUID.class),
                        student.name(),
                        money(amount),
                        money(allocated),
                        money(amount.subtract(allocated)),
                        row.get("method", String.class),
                        row.get("occurred_on", LocalDate.class).toString(),
                        row.get("status", String.class),
                        row.get("version", Long.class));
                  });
        });
  }

  @Override
  public List<Allocation> allocations(UUID enrollmentId) {
    var scope = actors.requireResourceScope("FUNDS");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var enrollment = enrollments.require(enrollmentId);
          if (!scope.readableSchoolIds().contains(enrollment.schoolId()))
            throw new AccessDeniedException("无该报名的资金读取权限");
          return sql.fetch(
                  "select id,receipt_id,enrollment_id,amount,status,created_at from funds.allocation"
                      + " where enrollment_id=? order by created_at, id",
                  enrollmentId)
              .map(
                  row ->
                      new Allocation(
                          row.get("id", UUID.class),
                          row.get("receipt_id", UUID.class),
                          row.get("enrollment_id", UUID.class),
                          money(row.get("amount", BigDecimal.class)),
                          row.get("status", String.class),
                          row.get("created_at", java.time.OffsetDateTime.class) == null
                              ? null
                              : row.get("created_at", java.time.OffsetDateTime.class)
                                  .toInstant()
                                  .toString()));
        });
  }

  @Override
  public String outstanding(UUID enrollmentId) {
    var scope = actors.requireResourceScope("FUNDS");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var enrollment = enrollments.require(enrollmentId);
          if (!scope.readableSchoolIds().contains(enrollment.schoolId()))
            throw new AccessDeniedException("无该报名的资金读取权限");
          return money(outstandingOf(enrollmentId));
        });
  }

  // ---------------------------------------------------------------- 写入

  @Override
  public Receipt createReceipt(CreateReceipt command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createReceiptWithTrace(command);
    }
  }

  private Receipt createReceiptWithTrace(CreateReceipt command) {
    if (command == null || command.requestId() == null || command.studentId() == null)
      throw new IllegalArgumentException("需要请求身份和学员");
    var amount = moneyAmount(command.amount(), "收款金额");
    var method = method(command.method());
    var occurredOn = businessDate(command.occurredOn(), "收款日期");
    var receipt = actors.requireAction("FUNDS_RECEIPT");
    return tx.execute(
        receipt.tenantId(),
        () -> {
          schools.lockTenant(receipt.tenantId());
          var verified = actors.requireActionInTransaction("FUNDS_RECEIPT");
          var student = students.require(command.studentId());
          var schoolId = student.schoolId();
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的收款权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能确认收款");
          lockStudent(student.id());
          var fingerprint =
              fingerprint(
                  student.id().toString(), amount.toPlainString(), method, occurredOn.toString());
          var previous = previousRequest(receipt, command.requestId(), fingerprint);
          if (previous != null) return receiptView(requireReceipt(previous), student.name());
          var id = UUID.randomUUID();
          sql.execute(
              "insert into funds.receipt(id,tenant_id,school_id,student_id,amount,method,occurred_on)"
                  + " values (?,?,?,?,?,?,?)",
              id,
              receipt.tenantId(),
              schoolId,
              student.id(),
              amount,
              method,
              occurredOn);
          sql.execute(
              "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                  + " values (?,?,'RECEIPT',?,?)",
              receipt.tenantId(),
              student.id(),
              amount,
              id);
          rememberRequest(receipt, command.requestId(), id, fingerprint);
          audit(receipt, "RECEIPT_CONFIRMED", id, schoolId, amount, "");
          return receiptView(requireReceipt(id), student.name());
        });
  }

  @Override
  public Receipt voidReceipt(UUID id, FundsVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return voidReceiptWithTrace(id, command);
    }
  }

  private Receipt voidReceiptWithTrace(UUID id, FundsVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要收款和版本");
    var receipt = actors.requireAction("FUNDS_RECEIPT");
    return tx.execute(
        receipt.tenantId(),
        () -> {
          schools.lockTenant(receipt.tenantId());
          var verified = actors.requireActionInTransaction("FUNDS_RECEIPT");
          var row = requireReceiptForUpdate(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的收款权限");
          var student = students.require(row.get("student_id", UUID.class));
          lockStudent(student.id());
          if (!"CONFIRMED".equals(row.get("status", String.class)))
            throw new FundsConflict("RECEIPT_NOT_CONFIRMED");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new FundsConflict("VERSION_CONFLICT");
          var active = activeAllocatedByReceipt(id);
          if (active.signum() > 0) throw new FundsConflict("RECEIPT_HAS_ALLOCATIONS");
          int updated =
              sql.execute(
                  "update funds.receipt set status='VOIDED',version=version+1"
                      + " where id=? and version=? and status='CONFIRMED'",
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new FundsConflict("VERSION_CONFLICT");
          var amount = row.get("amount", BigDecimal.class);
          sql.execute(
              "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                  + " values (?,?,'RECEIPT_VOID',?,?)",
              receipt.tenantId(),
              student.id(),
              amount.negate(),
              id);
          audit(receipt, "RECEIPT_VOIDED", id, schoolId, amount, "status");
          return receiptView(requireReceipt(id), student.name());
        });
  }

  @Override
  public Allocation allocate(AllocateReceipt command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return allocateWithTrace(command);
    }
  }

  private Allocation allocateWithTrace(AllocateReceipt command) {
    if (command == null || command.receiptId() == null || command.enrollmentId() == null)
      throw new IllegalArgumentException("需要收款和报名");
    var amount = moneyAmount(command.amount(), "分配金额");
    var allocate = actors.requireAction("FUNDS_ALLOCATE");
    return tx.execute(
        allocate.tenantId(),
        () -> {
          schools.lockTenant(allocate.tenantId());
          var verified = actors.requireActionInTransaction("FUNDS_ALLOCATE");
          var receipt = requireReceiptForUpdate(command.receiptId());
          var enrollment = enrollments.require(command.enrollmentId());
          var schoolId = receipt.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的分配权限");
          var studentId = receipt.get("student_id", UUID.class);
          lockStudent(studentId);
          if (!"CONFIRMED".equals(receipt.get("status", String.class)))
            throw new FundsConflict("RECEIPT_NOT_CONFIRMED");
          if (!studentId.equals(enrollment.studentId()))
            throw new FundsConflict("STUDENT_MISMATCH");
          if (!schoolId.equals(enrollment.schoolId())) throw new FundsConflict("SCHOOL_MISMATCH");
          if (!"ACTIVE".equals(enrollment.status()))
            throw new FundsConflict("ENROLLMENT_NOT_ACTIVE");
          var receiptAmount = receipt.get("amount", BigDecimal.class);
          var receiptRemaining =
              receiptAmount.subtract(activeAllocatedByReceipt(command.receiptId()));
          if (amount.compareTo(receiptRemaining) > 0)
            throw new FundsConflict("RECEIPT_INSUFFICIENT");
          var outstanding = outstandingOf(command.enrollmentId());
          if (amount.compareTo(outstanding) > 0) throw new FundsConflict("ENROLLMENT_OVERPAID");
          var id = UUID.randomUUID();
          sql.execute(
              "insert into funds.allocation(id,tenant_id,receipt_id,enrollment_id,amount)"
                  + " values (?,?,?,?,?)",
              id,
              allocate.tenantId(),
              command.receiptId(),
              command.enrollmentId(),
              amount);
          sql.execute(
              "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                  + " values (?,?,'ALLOCATION',?,?)",
              allocate.tenantId(),
              studentId,
              amount.negate(),
              id);
          audit(allocate, "ALLOCATION_CREATED", id, schoolId, amount, "");
          return allocationView(requireAllocation(id));
        });
  }

  @Override
  public Allocation releaseAllocation(UUID id) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return releaseWithTrace(id);
    }
  }

  private Allocation releaseWithTrace(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要分配");
    // 分配与释放共用同一动作：都属于把收款指向报名的操作。
    var allocate = actors.requireAction("FUNDS_ALLOCATE");
    return tx.execute(
        allocate.tenantId(),
        () -> {
          schools.lockTenant(allocate.tenantId());
          var verified = actors.requireActionInTransaction("FUNDS_ALLOCATE");
          var row = requireAllocationForUpdate(id);
          var receipt = requireReceipt(row.get("receipt_id", UUID.class));
          var schoolId = receipt.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的分配权限");
          var studentId = receipt.get("student_id", UUID.class);
          lockStudent(studentId);
          if (!"ACTIVE".equals(row.get("status", String.class)))
            throw new FundsConflict("ALLOCATION_NOT_ACTIVE");
          int updated =
              sql.execute(
                  "update funds.allocation set status='RELEASED',released_at=CURRENT_TIMESTAMP"
                      + " where id=? and status='ACTIVE'",
                  id);
          if (updated != 1) throw new FundsConflict("VERSION_CONFLICT");
          var amount = row.get("amount", BigDecimal.class);
          sql.execute(
              "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                  + " values (?,?,'RELEASE',?,?)",
              allocate.tenantId(),
              studentId,
              amount,
              id);
          audit(allocate, "ALLOCATION_RELEASED", id, schoolId, amount, "status");
          return allocationView(requireAllocation(id));
        });
  }

  // ---------------------------------------------------------------- 退款

  @Override
  public List<CashRefundView> cashRefunds(UUID enrollmentId) {
    var scope = actors.requireResourceScope("FUNDS");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var enrollment = enrollments.require(enrollmentId);
          if (!scope.readableSchoolIds().contains(enrollment.schoolId()))
            throw new AccessDeniedException("无该报名的资金读取权限");
          return sql.fetch(
                  "select id,enrollment_id,student_id,amount,status,reference,prepared_at,paid_at,version"
                      + " from funds.cash_refund where enrollment_id=? order by prepared_at, id",
                  enrollmentId)
              .map(this::cashRefundView);
        });
  }

  @Override
  public RefundResult cancelHoursAndReturnToAccount(
      UUID enrollmentId, CancelHoursAndReturnCommand command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return cancelHoursWithTrace(enrollmentId, command);
    }
  }

  /**
   * 退课时并退到账户。
   *
   * <p>顺序：校验可退课时（购买 − 已消 − 已退）→ 报名记录本次退量与应收减项 → 把分配调整到新的净应收， 差额留在学员账户。账户变化等于「释放的分配 −
   * 重新分配的金额」，等价于本次退费金额。
   */
  private RefundResult cancelHoursWithTrace(
      UUID enrollmentId, CancelHoursAndReturnCommand command) {
    if (enrollmentId == null || command == null || command.requestId() == null)
      throw new IllegalArgumentException("需要报名、课时与请求身份");
    var hours = moneyAmount(command.hours(), "退课时");
    var reason = text(command.reason(), 200, "退课时原因");
    var refund = actors.requireAction("REFUND_APPLY");
    return tx.execute(
        refund.tenantId(),
        () -> {
          schools.lockTenant(refund.tenantId());
          var verified = actors.requireActionInTransaction("REFUND_APPLY");
          var enrollment = enrollments.require(enrollmentId);
          var schoolId = enrollment.schoolId();
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的退课时权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能退课时");
          if (!"ACTIVE".equals(enrollment.status()))
            throw new FundsConflict("ENROLLMENT_NOT_ACTIVE");
          lockStudent(enrollment.studentId());
          var fingerprint =
              fingerprint(
                  "REFUND", enrollmentId.toString(), hours.toPlainString(), String.valueOf(reason));
          var previous = previousRequest(refund, command.requestId(), fingerprint, "REFUND");
          if (previous != null) return refundResult(enrollmentId);
          var consumed = consumption.consumed(enrollmentId).hours();
          var refundable =
              enrollment.hours().subtract(consumed).subtract(enrollment.cancelledHours());
          if (hours.compareTo(refundable) > 0) throw new FundsConflict("HOURS_EXCEED_REFUNDABLE");
          // 审批闸门：按策略要求校验并消耗审批单；金额由服务端计算，不接受调用方自报。
          var unit = enrollment.price().divide(enrollment.hours(), 6, RoundingMode.HALF_UP);
          var refundAmount = unit.multiply(hours).setScale(2, RoundingMode.HALF_UP);
          approvals.consume(
              "REFUND_HOURS", schoolId, enrollmentId, refundAmount, command.approvalId());
          var cancellation =
              enrollmentCommands.cancelHours(
                  enrollmentId, new CancelHours(command.hours(), reason));
          rebalance(
              refund,
              enrollmentId,
              schoolId,
              enrollment.studentId(),
              new BigDecimal(cancellation.netAmount()));
          rememberRequest(refund, command.requestId(), "REFUND", enrollmentId, fingerprint);
          audit(
              refund,
              "REFUND_REBALANCED",
              enrollmentId,
              schoolId,
              new BigDecimal(cancellation.amount()),
              "");
          return refundResult(enrollmentId);
        });
  }

  /**
   * 把某条报名的有效分配调整到目标金额。
   *
   * <p>先释放在用的全部分配（账户增加），再从可用收款里按时间倒序重新分配目标金额（账户减少）。 净效果是把多收的部分退回账户，未收部分继续挂在应收上。
   */
  private void rebalance(
      Actor actor, UUID enrollmentId, UUID schoolId, UUID studentId, BigDecimal target) {
    var active =
        sql.fetch(
            "select id,amount from funds.allocation where enrollment_id=? and status='ACTIVE' for update",
            enrollmentId);
    var released = BigDecimal.ZERO;
    for (var row : active) {
      var amount = row.get("amount", BigDecimal.class);
      sql.execute(
          "update funds.allocation set status='RELEASED',released_at=CURRENT_TIMESTAMP"
              + " where id=? and status='ACTIVE'",
          row.get("id", UUID.class));
      sql.execute(
          "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
              + " values (?,?,'RELEASE',?,?)",
          actor.tenantId(),
          studentId,
          amount,
          row.get("id", UUID.class));
      released = released.add(amount);
    }
    if (released.signum() > 0)
      audit(actor, "ALLOCATION_RELEASED", enrollmentId, schoolId, released, "refund");
    var remaining = target;
    if (remaining.signum() <= 0) return;
    var receipts =
        sql.fetch(
            "select r.id,r.amount,(select coalesce(sum(a.amount),0) from funds.allocation a"
                + " where a.receipt_id=r.id and a.status='ACTIVE') as allocated"
                + " from funds.receipt r where r.student_id=? and r.status='CONFIRMED'"
                + " order by r.occurred_on desc, r.id",
            studentId);
    for (var receipt : receipts) {
      if (remaining.signum() <= 0) break;
      var available =
          receipt
              .get("amount", BigDecimal.class)
              .subtract(receipt.get("allocated", BigDecimal.class));
      if (available.signum() <= 0) continue;
      var take = remaining.min(available);
      var allocationId = UUID.randomUUID();
      sql.execute(
          "insert into funds.allocation(id,tenant_id,receipt_id,enrollment_id,amount)"
              + " values (?,?,?,?,?)",
          allocationId,
          actor.tenantId(),
          receipt.get("id", UUID.class),
          enrollmentId,
          take);
      sql.execute(
          "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
              + " values (?,?,'ALLOCATION',?,?)",
          actor.tenantId(),
          studentId,
          take.negate(),
          allocationId);
      audit(actor, "ALLOCATION_CREATED", allocationId, schoolId, take, "refund");
      remaining = remaining.subtract(take);
    }
  }

  @Override
  public CashRefundView prepareCashRefund(PrepareCashRefund command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return prepareWithTrace(command);
    }
  }

  private CashRefundView prepareWithTrace(PrepareCashRefund command) {
    if (command == null || command.requestId() == null || command.enrollmentId() == null)
      throw new IllegalArgumentException("需要请求身份和报名");
    var amount = moneyAmount(command.amount(), "退款金额");
    var pay = actors.requireAction("REFUND_PAY");
    return tx.execute(
        pay.tenantId(),
        () -> {
          schools.lockTenant(pay.tenantId());
          var verified = actors.requireActionInTransaction("REFUND_PAY");
          var enrollment = enrollments.require(command.enrollmentId());
          var schoolId = enrollment.schoolId();
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的现金退款权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能准备退款");
          lockStudent(enrollment.studentId());
          var fingerprint =
              fingerprint("CASH_REFUND", command.enrollmentId().toString(), amount.toPlainString());
          var previous = previousRequest(pay, command.requestId(), fingerprint, "CASH_REFUND");
          if (previous != null) return cashRefundView(requireCashRefund(previous));
          var available = availableBalance(enrollment.studentId());
          if (amount.compareTo(available) > 0) throw new FundsConflict("INSUFFICIENT_AVAILABLE");
          approvals.consume(
              "CASH_REFUND", schoolId, command.enrollmentId(), amount, command.approvalId());
          var id = UUID.randomUUID();
          var reservationId = UUID.randomUUID();
          sql.execute(
              "insert into funds.reservation(id,tenant_id,student_id,amount,refund_id)"
                  + " values (?,?,?,?,?)",
              reservationId,
              pay.tenantId(),
              enrollment.studentId(),
              amount,
              id);
          sql.execute(
              "insert into funds.cash_refund(id,tenant_id,school_id,student_id,enrollment_id,"
                  + "reservation_id,amount) values (?,?,?,?,?,?,?)",
              id,
              pay.tenantId(),
              schoolId,
              enrollment.studentId(),
              command.enrollmentId(),
              reservationId,
              amount);
          rememberRequest(pay, command.requestId(), "CASH_REFUND", id, fingerprint);
          audit(pay, "RESERVATION_CREATED", id, schoolId, amount, "");
          return cashRefundView(requireCashRefund(id));
        });
  }

  @Override
  public CashRefundView confirmCashRefundPaid(UUID id, ConfirmCashRefund command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return confirmPaidWithTrace(id, command);
    }
  }

  private CashRefundView confirmPaidWithTrace(UUID id, ConfirmCashRefund command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要退款单和版本");
    var reference = text(command.reference(), 100, "支付凭据");
    if (reference == null) throw new IllegalArgumentException("需要实际支付凭据");
    var pay = actors.requireAction("REFUND_PAY");
    return tx.execute(
        pay.tenantId(),
        () -> {
          schools.lockTenant(pay.tenantId());
          var verified = actors.requireActionInTransaction("REFUND_PAY");
          var row = requireCashRefundForUpdate(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的现金退款权限");
          if (!"PREPARED".equals(row.get("status", String.class)))
            throw new FundsConflict("CASH_REFUND_NOT_PREPARED");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new FundsConflict("VERSION_CONFLICT");
          var amount = row.get("amount", BigDecimal.class);
          var studentId = row.get("student_id", UUID.class);
          lockStudent(studentId);
          int updated =
              sql.execute(
                  "update funds.cash_refund set status='PAID',reference=?,paid_at=CURRENT_TIMESTAMP,"
                      + "paid_by=?,version=version+1 where id=? and version=? and status='PREPARED'",
                  reference,
                  pay.accountId(),
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new FundsConflict("VERSION_CONFLICT");
          sql.execute(
              "update funds.reservation set status='SETTLED' where id=? and status='ACTIVE'",
              row.get("reservation_id", UUID.class));
          sql.execute(
              "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                  + " values (?,?,'CASH_REFUND',?,?)",
              pay.tenantId(),
              studentId,
              amount.negate(),
              id);
          audit(pay, "CASH_REFUND_PAID", id, schoolId, amount, "paid");
          return cashRefundView(requireCashRefund(id));
        });
  }

  @Override
  public CashRefundView cancelCashRefund(UUID id, FundsVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return cancelRefundWithTrace(id, command);
    }
  }

  private CashRefundView cancelRefundWithTrace(UUID id, FundsVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要退款单和版本");
    var pay = actors.requireAction("REFUND_PAY");
    return tx.execute(
        pay.tenantId(),
        () -> {
          schools.lockTenant(pay.tenantId());
          var verified = actors.requireActionInTransaction("REFUND_PAY");
          var row = requireCashRefundForUpdate(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的现金退款权限");
          if (!"PREPARED".equals(row.get("status", String.class)))
            throw new FundsConflict("CASH_REFUND_NOT_PREPARED");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new FundsConflict("VERSION_CONFLICT");
          int updated =
              sql.execute(
                  "update funds.cash_refund set status='CANCELLED',version=version+1"
                      + " where id=? and version=? and status='PREPARED'",
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new FundsConflict("VERSION_CONFLICT");
          sql.execute(
              "update funds.reservation set status='RELEASED' where id=? and status='ACTIVE'",
              row.get("reservation_id", UUID.class));
          audit(
              pay,
              "RESERVATION_RELEASED",
              id,
              schoolId,
              row.get("amount", BigDecimal.class),
              "cancelled");
          return cashRefundView(requireCashRefund(id));
        });
  }

  private RefundResult refundResult(UUID enrollmentId) {
    var enrollment = enrollments.require(enrollmentId);
    var allocated = activeAllocatedByEnrollment(enrollmentId);
    return new RefundResult(
        enrollmentId,
        hours(enrollment.cancelledHours()),
        money(enrollment.price().subtract(enrollment.netAmount())),
        money(enrollment.netAmount()),
        money(allocated),
        money(accountTotal(enrollment.studentId())),
        money(availableBalance(enrollment.studentId())));
  }

  private BigDecimal activeAllocatedByEnrollment(UUID enrollmentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from funds.allocation"
                    + " where enrollment_id=? and status='ACTIVE'",
                enrollmentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private Record requireCashRefund(UUID id) {
    var row =
        sql.fetchOne(
            "select id,enrollment_id,student_id,school_id,reservation_id,amount,status,reference,"
                + "prepared_at,paid_at,version from funds.cash_refund where id=?",
            id);
    if (row == null) throw new AccessDeniedException("退款单不存在或不可访问");
    return row;
  }

  private Record requireCashRefundForUpdate(UUID id) {
    var row =
        sql.fetchOne(
            "select id,enrollment_id,student_id,school_id,reservation_id,amount,status,reference,"
                + "prepared_at,paid_at,version from funds.cash_refund where id=? for update",
            id);
    if (row == null) throw new AccessDeniedException("退款单不存在或不可访问");
    return row;
  }

  private CashRefundView cashRefundView(Record row) {
    return new CashRefundView(
        row.get("id", UUID.class),
        row.get("enrollment_id", UUID.class),
        row.get("student_id", UUID.class),
        money(row.get("amount", BigDecimal.class)),
        row.get("status", String.class),
        row.get("reference", String.class),
        instant(row.get("prepared_at", java.time.OffsetDateTime.class)),
        instant(row.get("paid_at", java.time.OffsetDateTime.class)),
        row.get("version", Long.class));
  }

  private String instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private String hours(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  // ---------------------------------------------------------------- 内部

  private Set<UUID> schoolsOf(String action) {
    try {
      return actors.requireAction(action).schools();
    } catch (AccessDeniedException denied) {
      return Set.of();
    }
  }

  private void lockStudent(UUID studentId) {
    // 同一学员的资金动作串行，避免并发分配或撤销把同一笔钱用两次。
    sql.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))", "funds:" + studentId);
  }

  private BigDecimal accountTotal(UUID studentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from funds.account_entry where student_id=?",
                studentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal reservedTotal(UUID studentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from funds.reservation"
                    + " where student_id=? and status='ACTIVE'",
                studentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal availableBalance(UUID studentId) {
    return accountTotal(studentId).subtract(reservedTotal(studentId));
  }

  private BigDecimal activeAllocated(UUID studentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(a.amount),0) as total from funds.allocation a"
                    + " join funds.receipt r on r.id=a.receipt_id"
                    + " where r.student_id=? and a.status='ACTIVE'",
                studentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal activeAllocatedByReceipt(UUID receiptId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from funds.allocation"
                    + " where receipt_id=? and status='ACTIVE'",
                receiptId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal outstandingOf(UUID enrollmentId) {
    var allocated =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from funds.allocation"
                    + " where enrollment_id=? and status='ACTIVE'",
                enrollmentId)
            .get("total", BigDecimal.class);
    if (allocated == null) allocated = BigDecimal.ZERO;
    return enrollments.require(enrollmentId).netAmount().subtract(allocated);
  }

  private Record requireReceipt(UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,student_id,amount,method,occurred_on,status,version from funds.receipt where id=?",
            id);
    if (row == null) throw new AccessDeniedException("收款不存在或不可访问");
    return row;
  }

  private Record requireReceiptForUpdate(UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,student_id,amount,method,occurred_on,status,version from funds.receipt"
                + " where id=? for update",
            id);
    if (row == null) throw new AccessDeniedException("收款不存在或不可访问");
    return row;
  }

  private Record requireAllocation(UUID id) {
    var row =
        sql.fetchOne(
            "select id,receipt_id,enrollment_id,amount,status,created_at from funds.allocation where id=?",
            id);
    if (row == null) throw new AccessDeniedException("分配不存在或不可访问");
    return row;
  }

  private Record requireAllocationForUpdate(UUID id) {
    var row =
        sql.fetchOne(
            "select id,receipt_id,enrollment_id,amount,status from funds.allocation where id=? for update",
            id);
    if (row == null) throw new AccessDeniedException("分配不存在或不可访问");
    return row;
  }

  private Receipt receiptView(Record row, String studentName) {
    var amount = row.get("amount", BigDecimal.class);
    var allocated = activeAllocatedByReceipt(row.get("id", UUID.class));
    return new Receipt(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("student_id", UUID.class),
        studentName,
        money(amount),
        money(allocated),
        money(amount.subtract(allocated)),
        row.get("method", String.class),
        row.get("occurred_on", LocalDate.class).toString(),
        row.get("status", String.class),
        row.get("version", Long.class));
  }

  private Allocation allocationView(Record row) {
    return new Allocation(
        row.get("id", UUID.class),
        row.get("receipt_id", UUID.class),
        row.get("enrollment_id", UUID.class),
        money(row.get("amount", BigDecimal.class)),
        row.get("status", String.class),
        row.get("created_at", java.time.OffsetDateTime.class) == null
            ? null
            : row.get("created_at", java.time.OffsetDateTime.class).toInstant().toString());
  }

  private String method(String value) {
    var text = value == null ? "CASH" : value.strip().toUpperCase(Locale.ROOT);
    if (!METHODS.contains(text)) throw new IllegalArgumentException("收款方式无效");
    return text;
  }

  private BigDecimal moneyAmount(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    var text = value.strip();
    if (!DECIMAL.matcher(text).matches())
      throw new IllegalArgumentException(label + "须为最多两位小数的十进制数");
    var amount = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    if (amount.signum() <= 0) throw new IllegalArgumentException(label + "必须大于零");
    return amount;
  }

  private LocalDate businessDate(String value, String label) {
    if (value == null || value.isBlank()) return LocalDate.now();
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException(label + "须为 YYYY-MM-DD");
    }
  }

  private UUID previousRequest(Actor actor, UUID requestId, String fingerprint) {
    var row =
        sql.fetchOne(
            "select resource_id,fingerprint from funds.creation_request"
                + " where tenant_id=? and actor_id=? and request_id=?",
            actor.tenantId(),
            actor.accountId(),
            requestId);
    if (row == null) return null;
    if (!fingerprint.equals(row.get("fingerprint", String.class)))
      throw new FundsConflict("IDEMPOTENCY_MISMATCH");
    return row.get("resource_id", UUID.class);
  }

  private UUID previousRequest(Actor actor, UUID requestId, String fingerprint, String kind) {
    var row =
        sql.fetchOne(
            "select resource_id,fingerprint,kind from funds.creation_request"
                + " where tenant_id=? and actor_id=? and request_id=?",
            actor.tenantId(),
            actor.accountId(),
            requestId);
    if (row == null) return null;
    if (!fingerprint.equals(row.get("fingerprint", String.class))
        || !kind.equals(row.get("kind", String.class)))
      throw new FundsConflict("IDEMPOTENCY_MISMATCH");
    return row.get("resource_id", UUID.class);
  }

  private void rememberRequest(Actor actor, UUID requestId, UUID resource, String fingerprint) {
    rememberRequest(actor, requestId, "RECEIPT", resource, fingerprint);
  }

  private void rememberRequest(
      Actor actor, UUID requestId, String kind, UUID resource, String fingerprint) {
    sql.execute(
        "insert into funds.creation_request(tenant_id,actor_id,request_id,kind,resource_id,fingerprint)"
            + " values (?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        requestId,
        kind,
        resource,
        fingerprint);
  }

  private String fingerprint(String... parts) {
    var text = String.join("\u001f", parts);
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      var hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
      return hex.toString();
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("无法计算请求摘要", unavailable);
    }
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private void audit(
      Actor actor, String action, UUID resource, UUID school, BigDecimal amount, String fields) {
    sql.execute(
        "insert into audit.funds_event(tenant_id,actor_id,action,resource_id,school_id,amount,changed_fields,trace_id)"
            + " values (?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        amount,
        fields,
        RequestTrace.id());
  }

  private void audit(
      ResourceScope scope, String action, UUID resource, UUID school, String fields) {
    audit(scope.reader(), action, resource, school, null, fields);
  }
}
