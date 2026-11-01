package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

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
import org.teraschool.funds.api.*;
import org.teraschool.product.api.*;
import org.teraschool.teaching.api.*;

/**
 * 模式 4/5（退课后实际费用）：自定义退费额后按「剩余费用 ÷ 剩余课时」重算单价，并在课时耗尽时补齐尾差。
 *
 * <p>金标准取自旧系统帮助文本：100 课时 1000 元，退 50 课时退 200 元 → (1000−200)÷(100−50) = 160 元/课时。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementModeActualCostTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CHINESE = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired FundsCommands funds;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "实际费用机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','实际费用学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'语文')",
        CHINESE,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'实际费用产品',1)",
        PRODUCT,
        TENANT,
        SCHOOL,
        CHINESE);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        OPS,
        TENANT,
        "actual-ops",
        encoder.encode(PASSWORD));
    for (var action :
        new String[] {
          "PRODUCT_READ", "PRODUCT_MAINTAIN", "PRICE_PUBLISH", "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN", "ENROLLMENT_APPROVE", "FUNDS_READ", "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE", "TEACHING_READ", "TEACHING_MAINTAIN", "ATTENDANCE_RECORD",
          "LESSON_CLOSE", "REFUND_READ", "REFUND_APPLY"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
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

  EnrollmentView enrollment(int mode) throws Exception {
    var price =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        PRODUCT,
                        "1000.00",
                        "100.00",
                        "0.00",
                        mode,
                        null,
                        "2026-01-01",
                        null)));
    asActor(
        OPS, () -> catalog.publishPriceVersion(price.id(), new ExpectedVersion(price.version())));
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "S8" + student.toString().replace("-", "").substring(0, 6),
        "实际费用学员" + student.toString().substring(0, 4),
        "FEMALE",
        SCHOOL);
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, price.id(), "2026-02-10")));
    var approved =
        asActor(
            OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(), SCHOOL, student, "1000", "CASH", "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), approved.id(), "1000")));
    return approved;
  }

  private final Map<String, UUID> classes = new HashMap<>();
  private int lessonSeq;

  /** 建班（每门课程一次）并在班里上课关闭，返回累计消费金额。 */
  String consume(EnrollmentView enrollment, String hours) throws Exception {
    var existing = classes.get("语文");
    if (existing == null) {
      var classView =
          asActor(
              OPS,
              () ->
                  teaching.createClass(
                      new CreateClass(
                          UUID.randomUUID(), SCHOOL, CHINESE, "班" + UUID.randomUUID())));
      asActor(OPS, () -> teaching.addMember(classView.id(), new AddClassMember(enrollment.id())));
      existing = classView.id();
      classes.put("语文", existing);
    }
    final UUID classId = existing;
    var day = String.format("2026-04-%02d", ++lessonSeq + 1);
    var lesson =
        asActor(
            OPS,
            () ->
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classId,
                        day,
                        "09:00",
                        "10:00",
                        null,
                        null,
                        "课",
                        hours)));
    var published =
        asActor(
            OPS, () -> teaching.publishLesson(lesson.id(), new LessonVersion(lesson.version())));
    var confirmed =
        asActor(
            OPS,
            () ->
                teaching.confirmAttendance(
                    published.id(),
                    new ConfirmAttendance(
                        published.version(),
                        List.of(new AttendanceInput(enrollment.id(), "PRESENT")))));
    asActor(
        OPS, () -> teaching.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
    return asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id())).consumedAmount();
  }

  RefundResult refund(EnrollmentView enrollment, String hours, String amount) {
    return asActor(
        OPS,
        () ->
            funds.cancelHoursAndReturnToAccount(
                enrollment.id(),
                new CancelHoursAndReturnCommand(
                    UUID.randomUUID(), hours, "退课", null, null, amount)));
  }

  @Test
  void modeFourRecomputesUnitAfterRefund() throws Exception {
    var enrollment = enrollment(4);
    // 100 课时 1000 元，退 50 课时退 200 元 → 剩余 800 元 / 50 课时 = 16 元/课时。
    // （旧帮助文本写的 160 是它自己的算术笔误：800 ÷ 50 = 16。）
    var refunded = refund(enrollment, "50.00", "200.00");
    assertThat(refunded.amount()).isEqualTo("200.00");
    assertThat(refunded.netAmount()).isEqualTo("800.00");
    assertThat(consume(enrollment, "1.00")).isEqualTo("16.00");
  }

  @Test
  void modeFourFixesTrailingDifference() throws Exception {
    var enrollment = enrollment(4);
    refund(enrollment, "50.00", "200.00");
    assertThat(consume(enrollment, "1.00")).isEqualTo("16.00");
    // 剩余 49 课时一次上完：补齐到剩余应收 800.00。
    assertThat(consume(enrollment, "49.00")).isEqualTo("800.00");
  }

  @Test
  void modeFourStopsAtNetHours() throws Exception {
    var enrollment = enrollment(4);
    refund(enrollment, "50.00", "200.00");
    assertThat(consume(enrollment, "50.00")).isEqualTo("800.00");
    // 净课时 50 已用完，再上课不再消耗。
    assertThat(consume(enrollment, "1.00")).isEqualTo("800.00");
  }

  @Test
  void modeFiveUsesNetCourseAmount() throws Exception {
    var enrollment = enrollment(5);
    var refunded =
        asActor(
            OPS,
            () ->
                funds.cancelHoursAndReturnToAccount(
                    enrollment.id(),
                    new CancelHoursAndReturnCommand(
                        UUID.randomUUID(), "50.00", "退课", null, CHINESE, "200.00")));
    assertThat(refunded.amount()).isEqualTo("200.00");
    assertThat(consume(enrollment, "1.00")).isEqualTo("16.00");
  }

  @Test
  void refundRejectsAmountAboveRemaining() throws Exception {
    var enrollment = enrollment(4);
    assertThatThrownBy(() -> refund(enrollment, "10.00", "1200.00"))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("AMOUNT_EXCEED_REMAINING");
  }
}
