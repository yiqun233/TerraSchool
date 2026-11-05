package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.approval.api.*;
import org.teraschool.enrollment.api.*;
import org.teraschool.finance.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.*;

/** 财务期间：锁月拦截该月资金与消费写入、解锁需版本、权限与审计。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinancePeriodTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      PRICE = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      LOCKER = UUID.randomUUID(),
      READER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired PeriodCommands periodCommands;
  @Autowired PeriodQueries periodQueries;
  @Autowired FinancialReportQueries financialReports;
  @Autowired EnrollmentCommands enrollments;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired FundsCommands funds;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired ApprovalCommands approvalCommands;
  @Autowired org.jooq.DSLContext sql;
  @Autowired TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update(
        "insert into identity.tenant(id,name) values (?,?),(?,?)",
        TENANT,
        "期间机构甲",
        OTHER_TENANT,
        "期间机构乙");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','期间学校甲')",
        SCHOOL,
        TENANT,
        SCHOOL);
    EngineeringTest.seedExplicitApprovalPolicy(TENANT, SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'期间课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'期间产品',1)",
        PRODUCT,
        TENANT,
        SCHOOL,
        CURRICULUM);
    update(
        "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,"
            + " service_fee,settlement_mode,effective_from)"
            + " values (?,?,?,1,'PUBLISHED',1000.00,10.00,0,1,'2026-01-01')",
        PRICE,
        TENANT,
        PRODUCT);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry :
        Map.of(OPS, "period-ops", LOCKER, "period-locker", READER, "period-reader").entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var action :
        new String[] {
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "FUNDS_READ",
          "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE",
          "TEACHING_READ",
          "TEACHING_MAINTAIN",
          "ATTENDANCE_RECORD",
          "LESSON_CLOSE",
          "REFUND_READ",
          "REFUND_APPLY",
          "REFUND_PAY",
          "PERIOD_READ",
          "PERIOD_UNLOCK",
          "APPROVAL_READ"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
    for (var action : new String[] {"PERIOD_READ", "PERIOD_LOCK", "APPROVAL_DECIDE"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          LOCKER,
          SCHOOL,
          action);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'PERIOD_READ','ALLOW')",
        TENANT,
        READER,
        SCHOOL);
  }

  <T> T asActor(UUID account, Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            EngineeringTest.principal(account), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  UUID freshStudent() {
    var id = UUID.randomUUID();
    try {
      update(
          "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
          id,
          TENANT,
          "S5" + id.toString().replace("-", "").substring(0, 6),
          "期间学员" + id.toString().substring(0, 4),
          "FEMALE",
          SCHOOL);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return id;
  }

  EnrollmentView activeEnrollment(UUID student) {
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, PRICE, "2026-02-10")));
    return asActor(
        OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
  }

  Receipt receipt(UUID student, String date) {
    return asActor(
        OPS,
        () ->
            funds.createReceipt(
                new CreateReceipt(UUID.randomUUID(), SCHOOL, student, "1000", "CASH", date)));
  }

  PeriodView lock(String period) {
    return asActor(
        LOCKER, () -> periodCommands.lock(SCHOOL, new LockPeriod(SCHOOL, period, "月末结账", null)));
  }

  PeriodView unlock(String period, Long version) {
    return asActor(
        OPS, () -> periodCommands.unlock(SCHOOL, new LockPeriod(SCHOOL, period, "更正", version)));
  }

  @Test
  void fundsReconciliationBalancesOpeningReceiptsRefundsAllocationsAndAccount() throws Exception {
    var reportSchool = UUID.randomUUID();
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','对账隔离学校')",
        reportSchool,
        TENANT,
        reportSchool);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'FUNDS_READ','ALLOW'),(?,?,?,'FUNDS_RECEIPT','ALLOW')",
        TENANT,
        OPS,
        reportSchool,
        TENANT,
        OPS,
        reportSchool);
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "R" + student.toString().replace("-", "").substring(0, 8),
        "对账隔离学员",
        "FEMALE",
        reportSchool);
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(), reportSchool, student, "1000", "CASH", "2026-04-12")));
    var allocation = UUID.randomUUID();
    update(
        "insert into funds.allocation(id,tenant_id,receipt_id,enrollment_id,amount) values (?,?,?,?,?)",
        allocation,
        TENANT,
        receipt.id(),
        UUID.randomUUID(),
        new java.math.BigDecimal("300.00"));
    update(
        "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id) values "
            + "(?,?,'OPENING',100.00,?),(?,?,'ALLOCATION',-300.00,?),(?,?,'CASH_REFUND',-50.00,?)",
        TENANT,
        student,
        UUID.randomUUID(),
        TENANT,
        student,
        allocation,
        TENANT,
        student,
        UUID.randomUUID());

    var report = asActor(OPS, () -> financialReports.fundsReconciliation(reportSchool));
    assertThat(report.openingAmount()).isEqualTo("100.00");
    assertThat(report.netReceiptAmount()).isEqualTo("1000.00");
    assertThat(report.cashRefundAmount()).isEqualTo("50.00");
    assertThat(report.activeAllocatedAmount()).isEqualTo("300.00");
    assertThat(report.accountBalanceAmount()).isEqualTo("750.00");
    assertThat(report.differenceAmount()).isEqualTo("0.00");
    update(
        "update funds.account_entry set amount=-299.99 where student_id=? and kind='ALLOCATION'",
        student);
    assertThat(
            asActor(
                OPS, () -> financialReports.fundsReconciliation(reportSchool).differenceAmount()))
        .isEqualTo("-0.01");
    assertThatThrownBy(
            () -> asActor(READER, () -> financialReports.fundsReconciliation(reportSchool)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void receiptDailyReportGroupsGrossAmountsByDateMethodAndStatusWithinSchoolScope()
      throws Exception {
    var reportSchool = UUID.randomUUID();
    var student = UUID.randomUUID();
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','收款统计学校')",
        reportSchool,
        TENANT,
        reportSchool);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'FUNDS_READ','ALLOW')",
        TENANT,
        OPS,
        reportSchool);
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "S" + student.toString().replace("-", "").substring(0, 8),
        "收款统计学员",
        "FEMALE",
        reportSchool);
    update(
        "insert into funds.receipt(id,tenant_id,school_id,student_id,amount,method,occurred_on,status)"
            + " values (?,?,?,?,100.00,'CASH','2026-04-12','CONFIRMED'),"
            + "(?,?,?,?,50.00,'CASH','2026-04-12','CONFIRMED'),"
            + "(?,?,?,?,40.00,'BANK','2026-04-13','VOIDED')",
        UUID.randomUUID(),
        TENANT,
        reportSchool,
        student,
        UUID.randomUUID(),
        TENANT,
        reportSchool,
        student,
        UUID.randomUUID(),
        TENANT,
        reportSchool,
        student);

    var totals =
        asActor(
            OPS,
            () -> financialReports.receiptDailyTotals(reportSchool, "2026-04-12", "2026-04-13"));
    assertThat(totals)
        .containsExactly(
            new ReceiptDailyTotal("2026-04-12", "CASH", "CONFIRMED", 2, "150.00"),
            new ReceiptDailyTotal("2026-04-13", "BANK", "VOIDED", 1, "40.00"));
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        financialReports.receiptDailyTotals(
                            reportSchool, "2026-04-14", "2026-04-12")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("结束日期不能早于起始日期");
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        financialReports.receiptDailyTotals(
                            reportSchool, "2026-04-12", "2026-04-13")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void dailyOverviewSeparatesReceiptStatusesAndCountsSchoolOperations() throws Exception {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var day = "2001-02-03";
    update(
        "update enrollment.enrollment set created_at='2001-02-03 12:00:00+00' where id=?",
        enrollment.id());
    asActor(
        OPS,
        () -> {
          funds.createReceipt(
              new CreateReceipt(UUID.randomUUID(), SCHOOL, student, "500", "CASH", day));
          return null;
        });
    update(
        "insert into funds.receipt(id,tenant_id,school_id,student_id,amount,method,occurred_on,status)"
            + " values (?,?,?,?,'80.00','BANK',?,'VOIDED')",
        UUID.randomUUID(),
        TENANT,
        SCHOOL,
        student,
        java.time.LocalDate.parse(day));
    var classId = UUID.randomUUID();
    var lessonId = UUID.randomUUID();
    update(
        "insert into teaching.class(id,tenant_id,school_id,curriculum_id,name) values (?,?,?,?,?)",
        classId,
        TENANT,
        SCHOOL,
        CURRICULUM,
        "概况班级-" + classId.toString().substring(0, 8));
    update(
        "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,hours,status)"
            + " values (?,?,?,?,'09:00','10:00',1.00,'CLOSED')",
        lessonId,
        TENANT,
        classId,
        java.time.LocalDate.parse(day));
    update(
        "insert into teaching.attendance(tenant_id,lesson_id,enrollment_id,status) values (?,?,?,'PRESENT')",
        TENANT,
        lessonId,
        enrollment.id());

    var overview = asActor(OPS, () -> financialReports.dailyOverview(SCHOOL, day));
    assertThat(overview.enrollmentCount()).isEqualTo(1);
    assertThat(overview.confirmedReceiptCount()).isEqualTo(1);
    assertThat(overview.confirmedReceiptAmount()).isEqualTo("500.00");
    assertThat(overview.voidedReceiptCount()).isEqualTo(1);
    assertThat(overview.voidedReceiptAmount()).isEqualTo("80.00");
    assertThat(overview.lessonCount()).isEqualTo(1);
    assertThat(overview.closedLessonCount()).isEqualTo(1);
    assertThat(overview.attendanceRecordCount()).isEqualTo(1);
    assertThatThrownBy(() -> asActor(READER, () -> financialReports.dailyOverview(SCHOOL, day)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void studentConsumptionReportReconcilesPurchasedConsumedCancelledAndRemaining() throws Exception {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    update(
        "insert into teaching.consumption(id,tenant_id,enrollment_id,lesson_id,hours,amount,"
            + "settlement_mode,price_snapshot,hours_snapshot) values (?,?,?,null,2.00,200.00,1,1000.00,10.00)",
        UUID.randomUUID(),
        TENANT,
        enrollment.id());
    update(
        "insert into enrollment.hours_cancellation(tenant_id,enrollment_id,hours,amount,reason)"
            + " values (?,?,1.00,100.00,'验算退课')",
        TENANT,
        enrollment.id());

    var report = asActor(OPS, () -> teachingQueries.studentConsumption(SCHOOL, 0, 50));
    assertThat(report.total()).isGreaterThanOrEqualTo(1);
    assertThat(report.items())
        .anySatisfy(
            item ->
                assertThat(item)
                    .isEqualTo(
                        new TeachingQueries.StudentConsumptionRow(
                            enrollment.id(),
                            student,
                            "期间学员" + student.toString().substring(0, 4),
                            "ACTIVE",
                            1,
                            "1000.00",
                            "10.00",
                            "2.00",
                            "200.00",
                            "1.00",
                            "7.00")));
    assertThatThrownBy(
            () -> asActor(READER, () -> teachingQueries.studentConsumption(SCHOOL, 0, 50)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void attendanceReportAggregatesRecordedStatusesByLessonWithinSchool() throws Exception {
    var presentEnrollment = activeEnrollment(freshStudent());
    var absentEnrollment = activeEnrollment(freshStudent());
    var partialEnrollment = activeEnrollment(freshStudent());
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "考勤报表班" + UUID.randomUUID())));
    var lesson =
        asActor(
            OPS,
            () ->
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classView.id(),
                        "2026-06-12",
                        "09:00",
                        "10:00",
                        null,
                        null,
                        "考勤报表课",
                        "1.00")));
    update(
        "insert into teaching.attendance(tenant_id,lesson_id,enrollment_id,status,attended_minutes)"
            + " values (?,?,?,'PRESENT',null),(?,?,?,'ABSENT',null)",
        TENANT,
        lesson.id(),
        presentEnrollment.id(),
        TENANT,
        lesson.id(),
        absentEnrollment.id());
    update(
        "insert into teaching.attendance(tenant_id,lesson_id,enrollment_id,status,attended_minutes)"
            + " values (?,?,?,'PARTIAL',30)",
        TENANT,
        lesson.id(),
        partialEnrollment.id());

    var report =
        asActor(OPS, () -> teachingQueries.attendanceReport(SCHOOL, "2026-06-01", "2026-06-30"));
    assertThat(report.lessons())
        .containsExactly(
            new TeachingQueries.LessonAttendanceSummary(
                lesson.id(),
                classView.name(),
                "2026-06-12",
                "09:00:00",
                "10:00:00",
                "DRAFT",
                1,
                1,
                1,
                0,
                3));
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () -> teachingQueries.attendanceReport(SCHOOL, "2026-06-01", "2026-07-02")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () -> teachingQueries.attendanceReport(SCHOOL, "2026-06-01", "2026-06-30")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void approvedClassTransferMovesMembershipWithoutRewritingEnrollmentFunds() throws Exception {
    var enrollment = activeEnrollment(freshStudent());
    var source =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "转班源" + UUID.randomUUID())));
    var target =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "转班目标" + UUID.randomUUID())));
    asActor(OPS, () -> teaching.addMember(source.id(), new AddClassMember(enrollment.id())));
    update(
        "insert into approval.policy(tenant_id,school_id,action,free_below)"
            + " values (?,?,'ENROLLMENT_TRANSFER',0)",
        TENANT,
        SCHOOL);
    var approval =
        asActor(
            OPS,
            () ->
                approvalCommands.request(
                    new CreateApprovalRequest(
                        UUID.randomUUID(), enrollment.id(), "ENROLLMENT_TRANSFER", "0", "申请转班")));
    assertThat(approval.amount()).isEqualTo("0.00");
    var pendingTransfer =
        new TransferClassMember(
            UUID.randomUUID(), source.id(), target.id(), approval.id(), "同课程换班");
    assertThatThrownBy(
            () -> asActor(OPS, () -> teaching.transferMember(enrollment.id(), pendingTransfer)))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_NOT_APPROVED");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.class_transfer where enrollment_id=?",
                            enrollment.id())
                        .get(0, Long.class)))
        .isZero();
    assertThat(asActor(OPS, () -> teachingQueries.members(source.id()).getFirst().status()))
        .isEqualTo("ACTIVE");
    var approved =
        asActor(
            LOCKER,
            () ->
                approvalCommands.decide(
                    approval.id(), new DecideApproval(approval.version(), "APPROVED", "同意")));
    var approvedTransfer =
        new TransferClassMember(
            pendingTransfer.requestId(), source.id(), target.id(), approved.id(), "同课程换班");

    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    ClassView firstResult;
    ClassView secondResult;
    try {
      Callable<ClassView> submit =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            return asActor(OPS, () -> teaching.transferMember(enrollment.id(), approvedTransfer));
          };
      var first = pool.submit(submit);
      var second = pool.submit(submit);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult = first.get(10, TimeUnit.SECONDS);
      secondResult = second.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(firstResult.id()).isEqualTo(target.id());
    assertThat(secondResult.id()).isEqualTo(target.id());
    var memberships =
        asActor(
            OPS,
            () ->
                List.of(
                    teachingQueries.members(source.id()).getFirst().status(),
                    teachingQueries.members(target.id()).getFirst().status()));
    assertThat(memberships).containsExactly("LEFT", "ACTIVE");
    assertThat(asActor(OPS, () -> teaching.transferMember(enrollment.id(), approvedTransfer)).id())
        .isEqualTo(target.id());
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        teaching.transferMember(
                            enrollment.id(),
                            new TransferClassMember(
                                approvedTransfer.requestId(),
                                source.id(),
                                target.id(),
                                null,
                                "改参重放"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.class_transfer where enrollment_id=?",
                            enrollment.id())
                        .get(0, Long.class)))
        .isEqualTo(1);
    var afterTransfer = asActor(OPS, () -> enrollmentQueries.detail(enrollment.id()));
    assertThat(afterTransfer.price()).isEqualTo("1000.00");
    assertThat(afterTransfer.hours()).isEqualTo("10.00");
  }

  @Test
  void approvedClassWithdrawalEndsMembershipWithoutRewritingEnrollmentFunds() throws Exception {
    var enrollment = activeEnrollment(freshStudent());
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "退班" + UUID.randomUUID())));
    asActor(OPS, () -> teaching.addMember(classView.id(), new AddClassMember(enrollment.id())));
    update(
        "insert into approval.policy(tenant_id,school_id,action,free_below)"
            + " values (?,?,'ENROLLMENT_WITHDRAWAL',0)",
        TENANT,
        SCHOOL);
    var approval =
        asActor(
            OPS,
            () ->
                approvalCommands.request(
                    new CreateApprovalRequest(
                        UUID.randomUUID(), enrollment.id(), "ENROLLMENT_WITHDRAWAL", "0", "申请退班")));
    var pendingCommand =
        new WithdrawClassMember(UUID.randomUUID(), classView.id(), approval.id(), "家庭原因退班");
    assertThatThrownBy(
            () -> asActor(OPS, () -> teaching.withdrawMember(enrollment.id(), pendingCommand)))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_NOT_APPROVED");
    assertThat(asActor(OPS, () -> teachingQueries.members(classView.id()).getFirst().status()))
        .isEqualTo("ACTIVE");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.class_withdrawal where enrollment_id=?",
                            enrollment.id())
                        .get(0, Long.class)))
        .isZero();

    var approved =
        asActor(
            LOCKER,
            () ->
                approvalCommands.decide(
                    approval.id(), new DecideApproval(approval.version(), "APPROVED", "同意退班")));
    var approvedCommand =
        new WithdrawClassMember(
            pendingCommand.requestId(), classView.id(), approved.id(), "家庭原因退班");
    var result = asActor(OPS, () -> teaching.withdrawMember(enrollment.id(), approvedCommand));
    assertThat(result.id()).isEqualTo(classView.id());
    assertThat(asActor(OPS, () -> teachingQueries.members(classView.id()).getFirst().status()))
        .isEqualTo("LEFT");
    assertThat(asActor(OPS, () -> teaching.withdrawMember(enrollment.id(), approvedCommand)).id())
        .isEqualTo(classView.id());
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        teaching.withdrawMember(
                            enrollment.id(),
                            new WithdrawClassMember(
                                approvedCommand.requestId(),
                                classView.id(),
                                approved.id(),
                                "更改原因重放"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.class_withdrawal where enrollment_id=?",
                            enrollment.id())
                        .get(0, Long.class)))
        .isEqualTo(1);
    var afterWithdrawal = asActor(OPS, () -> enrollmentQueries.detail(enrollment.id()));
    assertThat(afterWithdrawal.price()).isEqualTo("1000.00");
    assertThat(afterWithdrawal.hours()).isEqualTo("10.00");
  }

  @Test
  void lockedMonthBlocksReceiptButKeepsOtherMonthsOpen() {
    lock("2026-03");
    assertThatThrownBy(() -> receipt(freshStudent(), "2026-03-10"))
        .isInstanceOf(PeriodConflict.class)
        .hasMessage("PERIOD_LOCKED");
    assertThat(receipt(freshStudent(), "2026-04-10").amount()).isEqualTo("1000.00");
  }

  @Test
  void lockedMonthBlocksLessonCloseByLessonDate() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "期间班" + UUID.randomUUID())));
    asActor(OPS, () -> teaching.addMember(classView.id(), new AddClassMember(enrollment.id())));
    var draft =
        asActor(
            OPS,
            () ->
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classView.id(),
                        "2026-05-06",
                        "09:00",
                        "10:00",
                        null,
                        null,
                        "课",
                        "1.00")));
    var published =
        asActor(OPS, () -> teaching.publishLesson(draft.id(), new LessonVersion(draft.version())));
    var confirmed =
        asActor(
            OPS,
            () ->
                teaching.confirmAttendance(
                    published.id(),
                    new ConfirmAttendance(
                        published.version(),
                        List.of(new AttendanceInput(enrollment.id(), "PRESENT")))));
    lock("2026-05");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        teaching.closeLesson(
                            confirmed.id(), new LessonVersion(confirmed.version()))))
        .isInstanceOf(PeriodConflict.class)
        .hasMessage("PERIOD_LOCKED");
  }

  @Test
  void lockedCurrentMonthBlocksRefundUntilUnlocked() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var receipt = receipt(student, "2026-02-10");
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000")));
    var period = YearMonth.now().toString();
    var locked = lock(period);
    assertThat(locked.status()).isEqualTo("LOCKED");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.cancelHoursAndReturnToAccount(
                            enrollment.id(),
                            new CancelHoursAndReturnCommand(
                                UUID.randomUUID(), "2.00", "退课时", null))))
        .isInstanceOf(PeriodConflict.class)
        .hasMessage("PERIOD_LOCKED");
    unlock(period, locked.version());
    assertThat(
            asActor(
                    OPS,
                    () ->
                        funds.cancelHoursAndReturnToAccount(
                            enrollment.id(),
                            new CancelHoursAndReturnCommand(
                                UUID.randomUUID(), "2.00", "退课时", null)))
                .amount())
        .isEqualTo("200.00");
  }

  @Test
  void unlockRequiresVersionAndLockedState() {
    var locked = lock("2026-06");
    assertThatThrownBy(() -> unlock("2026-06", null))
        .isInstanceOf(PeriodConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThatThrownBy(() -> unlock("2026-06", locked.version() + 9))
        .isInstanceOf(PeriodConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThat(unlock("2026-06", locked.version()).status()).isEqualTo("OPEN");
    assertThatThrownBy(() -> unlock("2026-06", locked.version() + 1))
        .isInstanceOf(PeriodConflict.class)
        .hasMessage("PERIOD_NOT_LOCKED");
  }

  @Test
  void lockingTwiceIsIdempotentAndAudited() throws Exception {
    lock("2026-07");
    var again = lock("2026-07");
    assertThat(again.status()).isEqualTo("LOCKED");
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement("select count(*) from audit.period_event where period='2026-07'")) {
      var r = s.executeQuery();
      r.next();
      assertThat(r.getLong(1)).isEqualTo(1);
    }
  }

  @Test
  void periodListCoversTwelveMonths() {
    lock("2026-08");
    var periods = asActor(READER, () -> periodQueries.periods(SCHOOL, "2026"));
    assertThat(periods).hasSize(12);
    assertThat(periods)
        .filteredOn(item -> item.period().equals("2026-08"))
        .extracting(PeriodView::status)
        .containsExactly("LOCKED");
    assertThat(periods)
        .filteredOn(item -> item.period().equals("2026-09"))
        .extracting(PeriodView::status)
        .containsExactly("OPEN");
  }

  @Test
  void lockNeedsPermission() {
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        periodCommands.lock(SCHOOL, new LockPeriod(SCHOOL, "2026-10", "无权", null))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into finance.period(tenant_id,school_id,period) values (?,?,'2026-11')",
                            TENANT,
                            SCHOOL)))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }
}
