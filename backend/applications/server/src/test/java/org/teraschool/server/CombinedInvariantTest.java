package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.enrollment.api.*;
import org.teraschool.finance.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.identity.api.AuthorizationManagement;
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.*;

/** REV-004：在真实普通角色事务中验证闸门、资金和履约的组合，不用线程启动顺序推断提交顺序。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(60)
class CombinedInvariantTest {
  @Autowired TenantTransactions tx;
  @Autowired DSLContext sql;
  @Autowired SchoolAccess schoolAccess;
  @Autowired SchoolManagement schools;
  @Autowired AuthorizationManagement authorizations;
  @Autowired EnrollmentCommands enrollments;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired FundsCommands funds;
  @Autowired FundsQueries fundsQueries;
  @Autowired PeriodCommands periods;

  static final String HASH =
      org.springframework.security.crypto.factory.PasswordEncoderFactories
          .createDelegatingPasswordEncoder()
          .encode(UUID.randomUUID().toString());
  static final List<String> ACTIONS =
      List.of(
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
          "PERIOD_LOCK",
          "MIGRATION_MANAGE");
  static final List<String> FACT_TABLES =
      List.of(
          "enrollment.enrollment",
          "enrollment.charge",
          "enrollment.hours_cancellation",
          "funds.receipt",
          "funds.allocation",
          "funds.account_entry",
          "funds.creation_request",
          "funds.reservation",
          "funds.cash_refund",
          "teaching.class",
          "teaching.enrollment_class",
          "teaching.lesson",
          "teaching.attendance",
          "teaching.consumption",
          "teaching.creation_request",
          "enrollment.creation_request",
          "audit.enrollment_event",
          "audit.funds_event",
          "audit.teaching_event");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  enum Write {
    CLOSE("LESSON_CLOSE"),
    REFUND("REFUND_APPLY"),
    RECEIPT("FUNDS_RECEIPT"),
    PAY("REFUND_PAY");
    final String action;

    Write(String action) {
      this.action = action;
    }
  }

  enum Gate {
    REVOKE,
    SUSPEND,
    LOCK_MONTH
  }

  record Fixture(
      UUID tenant,
      UUID school,
      UUID root,
      UUID operator,
      UUID student,
      EnrollmentView enrollment,
      LessonView lesson,
      CashRefundView cash) {}

  Fixture fixture() throws Exception {
    return fixture(null);
  }

  Fixture fixture(UUID existingTenant) throws Exception {
    UUID tenant = existingTenant == null ? UUID.randomUUID() : existingTenant,
        school = UUID.randomUUID(),
        root = UUID.randomUUID(),
        operator = UUID.randomUUID(),
        student = UUID.randomUUID(),
        course = UUID.randomUUID(),
        product = UUID.randomUUID(),
        price = UUID.randomUUID();
    if (existingTenant == null)
      update("insert into identity.tenant(id,name) values (?,'组合验证机构')", tenant);
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','组合学校')",
        school,
        tenant,
        school);
    EngineeringTest.seedExplicitApprovalPolicy(tenant, school);
    for (var user : List.of(root, operator))
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          user,
          tenant,
          user.toString(),
          HASH);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'AUTHORIZATION_MANAGE','ALLOW')",
        tenant,
        root,
        school);
    for (var action : ACTIONS)
      for (var user : List.of(root, operator))
        update(
            "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
            tenant,
            user,
            school,
            action);
    update(
        "insert into archive.student(id,tenant_id,school_id,student_no,name,sex) values (?,?,?,?,'组合学员','FEMALE')",
        student,
        tenant,
        school,
        student.toString().substring(0, 18));
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'组合课程')",
        course,
        tenant,
        school);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode) values (?,?,?,?,'组合产品',1)",
        product,
        tenant,
        school,
        course);
    update(
        "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,service_fee,settlement_mode,effective_from) values (?,?,?,1,'PUBLISHED',1000,10,0,1,'2026-01-01')",
        price,
        tenant,
        product);
    return as(
        operator,
        () -> {
          var draft =
              enrollments.create(
                  new CreateEnrollment(UUID.randomUUID(), student, price, "2026-02-10"));
          var enrollment = enrollments.approve(draft.id(), new EnrollmentVersion(draft.version()));
          var receipt =
              funds.createReceipt(
                  new CreateReceipt(
                      UUID.randomUUID(), school, student, "1000", "CASH", "2026-02-10"));
          funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000"));
          var group =
              teaching.createClass(new CreateClass(UUID.randomUUID(), school, course, "组合班"));
          teaching.addMember(group.id(), new AddClassMember(enrollment.id()));
          var lesson =
              teaching.createLesson(
                  new CreateLesson(
                      UUID.randomUUID(),
                      group.id(),
                      LocalDate.now().toString(),
                      "09:00",
                      "10:00",
                      null,
                      null,
                      "组合课",
                      "1.00"));
          var published = teaching.publishLesson(lesson.id(), new LessonVersion(lesson.version()));
          var confirmed =
              teaching.confirmAttendance(
                  published.id(),
                  new ConfirmAttendance(
                      published.version(),
                      List.of(new AttendanceInput(enrollment.id(), "PRESENT"))));
          funds.cancelHoursAndReturnToAccount(
              enrollment.id(),
              new CancelHoursAndReturnCommand(UUID.randomUUID(), "2", "预置退款", null));
          var cash =
              funds.prepareCashRefund(
                  new PrepareCashRefund(UUID.randomUUID(), enrollment.id(), "200", null));
          return new Fixture(tenant, school, root, operator, student, enrollment, confirmed, cash);
        });
  }

  <T> T as(UUID account, Supplier<T> work) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            EngineeringTest.principal(account), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  Object write(Fixture f, Write write, UUID request) {
    return switch (write) {
      case CLOSE -> teaching.closeLesson(f.lesson.id(), new LessonVersion(f.lesson.version()));
      case REFUND ->
          funds.cancelHoursAndReturnToAccount(
              f.enrollment.id(), new CancelHoursAndReturnCommand(request, "2", "组合退款", null));
      case RECEIPT ->
          funds.createReceipt(
              new CreateReceipt(
                  request, f.school, f.student, "100", "CASH", LocalDate.now().toString()));
      case PAY ->
          funds.confirmCashRefundPaid(
              f.cash.id(), new ConfirmCashRefund(f.cash.version(), "COMBINED-PAID"));
    };
  }

  Object gate(Fixture f, Gate gate, Write write) {
    return as(
        f.root,
        () ->
            switch (gate) {
              case REVOKE ->
                  authorizations.replace(
                      f.operator,
                      new AuthorizationManagement.Replacement(
                          0L,
                          ACTIONS.stream()
                              .filter(action -> !action.equals(write.action))
                              .map(
                                  action ->
                                      new AuthorizationManagement.Rule(action, f.school, "ALLOW"))
                              .toList(),
                          List.of()));
              case SUSPEND ->
                  schools.updateSchool(
                      f.school, new SchoolManagement.UpdateSchool(0L, "组合学校", false));
              case LOCK_MONTH ->
                  periods.lock(
                      f.school, new LockPeriod(f.school, YearMonth.now().toString(), "组合锁月", null));
            });
  }

  static Stream<Arguments> gatesAndWrites() {
    return Arrays.stream(Gate.values())
        .flatMap(gate -> Arrays.stream(Write.values()).map(write -> Arguments.of(gate, write)));
  }

  @ParameterizedTest(name = "{0} 提交后，排队的 {1} 必须拒绝且无残留")
  @MethodSource("gatesAndWrites")
  void queuedWriteRechecksCommittedGate(Gate gate, Write write) throws Exception {
    var f = fixture();
    var before = facts(f);
    var result =
        ordered(f, () -> gate(f, gate, write), () -> write(f, write, UUID.randomUUID()), false);
    if (gate == Gate.LOCK_MONTH)
      assertThat(result.failure).isInstanceOf(PeriodConflict.class).hasMessage("PERIOD_LOCKED");
    else assertThat(result.failure).isInstanceOf(AccessDeniedException.class);
    assertThat(facts(f)).as("拒绝后金额、课时、版本、请求账本和成功审计均保持原状").isEqualTo(before);
  }

  @ParameterizedTest(name = "{1} 先提交，随后 {0} 生效")
  @MethodSource("gatesAndWrites")
  void writeAlreadyHoldingLockCompletesBeforeGate(Gate gate, Write write) throws Exception {
    var f = fixture();
    var result =
        ordered(f, () -> write(f, write, UUID.randomUUID()), () -> gate(f, gate, write), false);
    assertThat(result.failure).isNull();
    assertWrite(f, write);
    var before = facts(f);
    var next = gate == Gate.LOCK_MONTH ? Write.RECEIPT : write;
    assertThatThrownBy(() -> as(f.operator, () -> write(f, next, UUID.randomUUID())))
        .isInstanceOf(gate == Gate.LOCK_MONTH ? PeriodConflict.class : AccessDeniedException.class);
    assertThat(facts(f)).isEqualTo(before);
  }

  void assertWrite(Fixture f, Write write) {
    as(
        f.root,
        () -> {
          var reconciliation = teachingQueries.reconciliation(f.enrollment.id());
          var balance = fundsQueries.balance(f.student);
          assertThat(reconciliation.consumedHours())
              .isEqualTo(write == Write.CLOSE ? "1.00" : "0.00");
          assertThat(reconciliation.cancelledHours())
              .isEqualTo(write == Write.REFUND ? "4.00" : "2.00");
          assertThat(balance.total())
              .isEqualTo(
                  switch (write) {
                    case REFUND -> "400.00";
                    case PAY -> "0.00";
                    case RECEIPT -> "300.00";
                    default -> "200.00";
                  });
          assertThat(balance.reserved()).isEqualTo(write == Write.PAY ? "0.00" : "200.00");
          return null;
        });
  }

  record Outcome(Object value, Throwable failure) {}

  /** 第一命令完成但不提交；确认第二连接确实等待第一连接的 advisory 锁，再释放或回滚。 */
  Outcome ordered(Fixture f, Supplier<?> first, Supplier<?> second, boolean rollback)
      throws Exception {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pid = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holder =
          pool.submit(
              () ->
                  as(
                      f.operator,
                      () -> {
                        try {
                          return tx.execute(
                              f.tenant,
                              () -> {
                                schoolAccess.lockTenant(f.tenant);
                                pid.set(
                                    sql.fetchOne("select pg_backend_pid()").get(0, Integer.class));
                                var value = first.get();
                                held.countDown();
                                await(release);
                                if (rollback) throw new IllegalStateException("INJECTED_ROLLBACK");
                                return value;
                              });
                        } catch (IllegalStateException error) {
                          if (!rollback || !"INJECTED_ROLLBACK".equals(error.getMessage()))
                            throw error;
                          return null;
                        } finally {
                          held.countDown();
                        }
                      }));
      assertThat(held.await(15, TimeUnit.SECONDS)).as("首个命令已完成并持锁").isTrue();
      if (holder.isDone()) holder.get(1, TimeUnit.SECONDS);
      var queued =
          pool.submit(
              () ->
                  as(
                      f.operator,
                      () -> {
                        try {
                          return new Outcome(second.get(), null);
                        } catch (RuntimeException error) {
                          return new Outcome(null, error);
                        }
                      }));
      awaitBlocked(pid.get());
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);
      return queued.get(15, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("等待提交许可超时");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  double awaitBlocked(int holder) throws Exception {
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement(
                "select extract(epoch from clock_timestamp()-query_start)*1000 from pg_stat_activity where usename='ts_app' and wait_event='advisory' and ? = any(pg_blocking_pids(pid)) limit 1")) {
      s.setInt(1, holder);
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        try (var rows = s.executeQuery()) {
          if (rows.next()) return rows.getDouble(1);
        }
        Thread.sleep(10);
      }
      fail("第二连接未在本用例的机构锁上排队，holder=" + holder);
      return 0;
    }
  }

  Map<String, String> facts(Fixture f) throws Exception {
    var result = new LinkedHashMap<String, String>();
    try (var c = EngineeringTest.admin()) {
      for (var table : FACT_TABLES)
        try (var s =
            c.prepareStatement(
                "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,'[]') from "
                    + table
                    + " t where tenant_id=?")) {
          s.setObject(1, f.tenant);
          try (var rows = s.executeQuery()) {
            rows.next();
            result.put(table, rows.getString(1));
          }
        }
    }
    return result;
  }

  @ParameterizedTest
  @EnumSource(Write.class)
  void failedHolderRollsBackAndQueuedSameCommandCanSucceed(Write write) throws Exception {
    var f = fixture();
    var request = UUID.randomUUID();
    var result = ordered(f, () -> write(f, write, request), () -> write(f, write, request), true);
    assertThat(result.failure).isNull();
    assertWrite(f, write);
  }

  @ParameterizedTest
  @EnumSource(Gate.class)
  void rolledBackGateDoesNotRejectQueuedWrite(Gate gate) throws Exception {
    var f = fixture();
    var result =
        ordered(
            f,
            () -> gate(f, gate, Write.CLOSE),
            () -> write(f, Write.CLOSE, UUID.randomUUID()),
            true);
    assertThat(result.failure).isNull();
    assertWrite(f, Write.CLOSE);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void closingAndRefundingCannotConsumeTheSameRemainingHours(boolean closeFirst) throws Exception {
    var f = fixture();
    Supplier<?> close = () -> write(f, Write.CLOSE, UUID.randomUUID());
    Supplier<?> refund =
        () ->
            funds.cancelHoursAndReturnToAccount(
                f.enrollment.id(),
                new CancelHoursAndReturnCommand(UUID.randomUUID(), "7.50", "争用剩余课时", null));
    var result = ordered(f, closeFirst ? close : refund, closeFirst ? refund : close, false);
    if (closeFirst)
      assertThat(result.failure)
          .isInstanceOf(FundsConflict.class)
          .hasMessage("HOURS_EXCEED_REFUNDABLE");
    else
      assertThat(result.failure)
          .isInstanceOf(TeachingConflict.class)
          .hasMessage("LESSON_HOURS_EXCEED_REMAINING");
    as(
        f.root,
        () -> {
          var r = teachingQueries.reconciliation(f.enrollment.id());
          assertThat(r.consumedHours()).isEqualTo(closeFirst ? "1.00" : "0.00");
          assertThat(r.cancelledHours()).isEqualTo(closeFirst ? "2.00" : "9.50");
          assertThat(r.remainingHours()).isEqualTo(closeFirst ? "7.00" : "0.50");
          return null;
        });
  }

  @ParameterizedTest
  @EnumSource(
      value = Write.class,
      names = {"CLOSE", "REFUND", "RECEIPT", "PAY"})
  void concurrentDuplicateDoesNotRepeatFacts(Write write) throws Exception {
    var f = fixture();
    var request = UUID.randomUUID();
    var result = ordered(f, () -> write(f, write, request), () -> write(f, write, request), false);
    if (write == Write.CLOSE)
      assertThat(result.failure)
          .isInstanceOf(TeachingConflict.class)
          .hasMessage("LESSON_NOT_CONFIRMED");
    else if (write == Write.PAY)
      assertThat(result.failure)
          .isInstanceOf(FundsConflict.class)
          .hasMessage("CASH_REFUND_NOT_PREPARED");
    else assertThat(result.failure).isNull();
    assertWrite(f, write);
    assertThat(count(f, "teaching.consumption")).isEqualTo(write == Write.CLOSE ? 1 : 0);
    assertThat(count(f, "enrollment.hours_cancellation")).isEqualTo(write == Write.REFUND ? 2 : 1);
    assertThat(count(f, "funds.receipt")).isEqualTo(write == Write.RECEIPT ? 2 : 1);
    assertThat(count(f, "funds.account_entry", " and kind='CASH_REFUND'"))
        .isEqualTo(write == Write.PAY ? 1 : 0);
  }

  long count(Fixture f, String table, String... suffix) throws Exception {
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement(
                "select count(*) from " + table + " where tenant_id=?" + String.join("", suffix))) {
      s.setObject(1, f.tenant);
      try (var rows = s.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  @Test
  void failureAfterCrossDomainWritesRollsBackFactsRequestsAndSuccessAudits() throws Exception {
    var f = fixture();
    var before = facts(f);
    var request = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                as(
                    f.operator,
                    () ->
                        tx.execute(
                            f.tenant,
                            () -> {
                              write(f, Write.CLOSE, request);
                              write(f, Write.REFUND, request);
                              write(f, Write.PAY, request);
                              gate(f, Gate.LOCK_MONTH, Write.CLOSE);
                              throw new IllegalStateException("INJECTED_ROLLBACK");
                            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("INJECTED_ROLLBACK");
    assertThat(facts(f)).isEqualTo(before);
    assertThat(count(f, "finance.period")).isZero();
    assertThat(count(f, "audit.period_event")).isZero();
    as(f.operator, () -> write(f, Write.REFUND, request));
    assertWrite(f, Write.REFUND);
  }

  @ParameterizedTest
  @EnumSource(Write.class)
  void auditFailureRollsBackEarlierDomainWritesAndAllowsSameRequestRetry(Write write)
      throws Exception {
    var f = fixture();
    var before = facts(f);
    var request = UUID.randomUUID();
    var name = "rev004_fail_" + UUID.randomUUID().toString().replace("-", "");
    var table = write == Write.CLOSE ? "audit.teaching_event" : "audit.funds_event";
    var action =
        switch (write) {
          case CLOSE -> "LESSON_CLOSED";
          case REFUND -> "REFUND_REBALANCED";
          case RECEIPT -> "RECEIPT_CONFIRMED";
          case PAY -> "CASH_REFUND_PAID";
        };
    // 仅在可销毁测试库为本用例租户注入最后一道成功审计写入失败，验证前面的真实写入一并回滚。
    try (var c = EngineeringTest.admin();
        var s = c.createStatement()) {
      s.execute(
          "create function audit."
              + name
              + "() returns trigger language plpgsql as $$ begin if NEW.tenant_id='"
              + f.tenant
              + "'::uuid and NEW.action='"
              + action
              + "' then raise exception 'REV004_INJECTED_AUDIT_FAILURE'; end if; return NEW; end $$");
      try {
        s.execute(
            "create trigger "
                + name
                + " before insert on "
                + table
                + " for each row execute function audit."
                + name
                + "()");
        try {
          assertThatThrownBy(() -> as(f.operator, () -> write(f, write, request)))
              .isInstanceOf(org.jooq.exception.DataAccessException.class)
              .satisfies(
                  error -> {
                    Throwable root = error;
                    while (root.getCause() != null) root = root.getCause();
                    assertThat(root).isInstanceOf(java.sql.SQLException.class);
                    assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("P0001");
                  });
          assertThat(facts(f)).isEqualTo(before);
        } finally {
          s.execute("drop trigger " + name + " on " + table);
        }
      } finally {
        s.execute("drop function audit." + name + "()");
      }
    }
    as(f.operator, () -> write(f, write, request));
    assertWrite(f, write);
  }

  @Test
  void suspendedSchoolRejectsQueuedCashRefundCancellation() throws Exception {
    var f = fixture();
    var before = facts(f);
    var result =
        ordered(
            f,
            () -> gate(f, Gate.SUSPEND, Write.PAY),
            () -> funds.cancelCashRefund(f.cash.id(), new FundsVersion(f.cash.version())),
            false);
    assertThat(result.failure).isInstanceOf(AccessDeniedException.class);
    assertThat(facts(f)).isEqualTo(before);
  }

  enum OtherWrite {
    PUBLISH,
    ATTENDANCE,
    OPENING,
    VOID_RECEIPT,
    ALLOCATE,
    RELEASE
  }

  Supplier<?> pending(Fixture f, OtherWrite write) {
    return as(
        f.operator,
        () -> {
          if (write == OtherWrite.PUBLISH || write == OtherWrite.ATTENDANCE) {
            var draft =
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        f.lesson.classId(),
                        LocalDate.now().toString(),
                        "11:00",
                        "12:00",
                        null,
                        null,
                        "停校保护课",
                        "1"));
            if (write == OtherWrite.PUBLISH)
              return () -> teaching.publishLesson(draft.id(), new LessonVersion(draft.version()));
            var published = teaching.publishLesson(draft.id(), new LessonVersion(draft.version()));
            return () ->
                teaching.confirmAttendance(
                    published.id(),
                    new ConfirmAttendance(
                        published.version(),
                        List.of(new AttendanceInput(f.enrollment.id(), "PRESENT"))));
          }
          return switch (write) {
            case OPENING ->
                () -> {
                  teaching.openingConsumption(
                      f.enrollment.id(),
                      new OpeningConsumption("1", "100", "停校保护期初", LocalDate.now().toString()));
                  return null;
                };
            case VOID_RECEIPT -> {
              var receipt = (Receipt) write(f, Write.RECEIPT, UUID.randomUUID());
              yield () -> funds.voidReceipt(receipt.id(), new FundsVersion(receipt.version()));
            }
            case ALLOCATE, RELEASE -> {
              var active =
                  fundsQueries.allocations(f.enrollment.id()).stream()
                      .filter(item -> item.status().equals("ACTIVE"))
                      .findFirst()
                      .orElseThrow();
              if (write == OtherWrite.RELEASE) yield () -> funds.releaseAllocation(active.id());
              funds.releaseAllocation(active.id());
              yield () ->
                  funds.allocate(new AllocateReceipt(active.receiptId(), f.enrollment.id(), "100"));
            }
            default -> throw new IllegalArgumentException("未支持的写入");
          };
        });
  }

  @ParameterizedTest(name = "停校拦截排队的 {0}；恢复后原命令成功")
  @EnumSource(OtherWrite.class)
  void allRemainingTeachingAndFundsWritesRespectSchoolSuspension(OtherWrite write)
      throws Exception {
    var f = fixture();
    var pending = pending(f, write);
    var before = facts(f);
    var result = ordered(f, () -> gate(f, Gate.SUSPEND, Write.CLOSE), pending, false);
    assertThat(result.failure).isInstanceOf(AccessDeniedException.class);
    assertThat(facts(f)).isEqualTo(before);
    as(
        f.root,
        () -> schools.updateSchool(f.school, new SchoolManagement.UpdateSchool(1L, "组合学校", true)));
    as(f.operator, pending);
    assertThat(facts(f)).as("恢复后同一份有效输入可继续业务").isNotEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void payingAndCancellingOneRefundCannotBothCommit(boolean payFirst) throws Exception {
    var f = fixture();
    Supplier<?> pay = () -> write(f, Write.PAY, UUID.randomUUID());
    Supplier<?> cancel =
        () -> funds.cancelCashRefund(f.cash.id(), new FundsVersion(f.cash.version()));
    var result = ordered(f, payFirst ? pay : cancel, payFirst ? cancel : pay, false);
    assertThat(result.failure)
        .isInstanceOf(FundsConflict.class)
        .hasMessage("CASH_REFUND_NOT_PREPARED");
    as(
        f.root,
        () -> {
          var balance = fundsQueries.balance(f.student);
          assertThat(balance.total()).isEqualTo(payFirst ? "0.00" : "200.00");
          assertThat(balance.reserved()).isEqualTo("0.00");
          assertThat(fundsQueries.cashRefunds(f.enrollment.id()))
              .singleElement()
              .extracting(CashRefundView::status)
              .isEqualTo(payFirst ? "PAID" : "CANCELLED");
          return null;
        });
    assertThat(count(f, "funds.account_entry", " and kind='CASH_REFUND'"))
        .isEqualTo(payFirst ? 1 : 0);
  }

  @Test
  void tenantLockSerializesDifferentSchoolsAndLetsOtherTenantProceed() throws Exception {
    var f = fixture();
    var sameTenant = fixture(f.tenant);
    var otherTenant = fixture();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pid = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holder =
          pool.submit(
              () ->
                  tx.execute(
                      f.tenant,
                      () -> {
                        schoolAccess.lockTenant(f.tenant);
                        pid.set(sql.fetchOne("select pg_backend_pid()").get(0, Integer.class));
                        held.countDown();
                        await(release);
                        return null;
                      }));
      assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
      // 连接池只有两条，先证实异机构能提交，再让同机构另一学校占据第二条连接排队。
      var independent =
          pool.submit(
              () ->
                  as(
                      otherTenant.operator,
                      () -> write(otherTenant, Write.RECEIPT, UUID.randomUUID())));
      assertThat(independent.get(10, TimeUnit.SECONDS)).isInstanceOf(Receipt.class);
      assertThat(holder.isDone()).isFalse();
      var queued =
          pool.submit(
              () ->
                  as(
                      sameTenant.operator,
                      () -> write(sameTenant, Write.RECEIPT, UUID.randomUUID())));
      double waited = awaitBlocked(pid.get());
      assertThat(queued.isDone()).isFalse();
      System.out.printf(
          Locale.ROOT,
          "REV004_LOCK_WAIT same_tenant_other_school=true observed_wait_ms=%.2f other_tenant_committed_while_held=true%n",
          waited);
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);
      assertThat(queued.get(15, TimeUnit.SECONDS)).isInstanceOf(Receipt.class);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest(name = "机构锁本机小样本：同机构={0}")
  @ValueSource(booleans = {true, false})
  void recordBoundedReceiptContentionSample(boolean sameTenant) throws Exception {
    var first = fixture();
    var second = fixture(sameTenant ? first.tenant : null);
    for (int concurrency : new int[] {1, 2}) {
      var samples = new ConcurrentLinkedQueue<Long>();
      var start = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(concurrency);
      long began = System.nanoTime();
      try {
        var tasks = new ArrayList<Future<?>>();
        for (int thread = 0; thread < concurrency; thread++) {
          var f = thread == 0 ? first : second;
          tasks.add(
              pool.submit(
                  () ->
                      as(
                          f.operator,
                          () -> {
                            await(start);
                            for (int n = 0; n < 20; n++) {
                              long before = System.nanoTime();
                              write(f, Write.RECEIPT, UUID.randomUUID());
                              samples.add(System.nanoTime() - before);
                            }
                            return null;
                          })));
        }
        start.countDown();
        for (var task : tasks) task.get(20, TimeUnit.SECONDS);
      } finally {
        start.countDown();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      }
      var sorted = samples.stream().sorted().toList();
      assertThat(sorted).hasSize(20 * concurrency);
      double elapsed = (System.nanoTime() - began) / 1_000_000.0;
      System.out.printf(
          Locale.ROOT,
          "REV004_RECEIPT_SAMPLE same_tenant=%s concurrency=%d operations=%d elapsed_ms=%.2f ops_per_second=%.2f p50_ms=%.2f p95_ms=%.2f%n",
          sameTenant,
          concurrency,
          sorted.size(),
          elapsed,
          sorted.size() * 1000 / elapsed,
          sorted.get((sorted.size() - 1) / 2) / 1_000_000.0,
          sorted.get((int) Math.ceil(sorted.size() * .95) - 1) / 1_000_000.0);
    }
    // 20 条单并发 + 20 条双并发落在 first，另 20 条落在 second；同机构时计数包含两所学校。
    assertThat(count(first, "funds.receipt")).isEqualTo(sameTenant ? 62 : 41);
    if (!sameTenant) assertThat(count(second, "funds.receipt")).isEqualTo(21);
  }
}
