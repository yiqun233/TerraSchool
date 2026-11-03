package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
  @Autowired EnrollmentCommands enrollments;
  @Autowired FundsCommands funds;
  @Autowired TeachingCommands teaching;
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
          "PERIOD_UNLOCK"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
    for (var action : new String[] {"PERIOD_READ", "PERIOD_LOCK"})
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
