package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * <p>参考旧系统帮助文本：100 课时 1000 元，退 50 课时退 200 元 → (1000−200)÷(100−50) = 16 元/课时。
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
  @Autowired FundsQueries fundsQueries;

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
    EngineeringTest.seedExplicitApprovalPolicy(TENANT, SCHOOL);
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
          "PRODUCT_READ",
          "PRODUCT_MAINTAIN",
          "PRICE_PUBLISH",
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "ENROLLMENT_DISCOUNT",
          "FUNDS_READ",
          "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE",
          "TEACHING_READ",
          "TEACHING_MAINTAIN",
          "ATTENDANCE_RECORD",
          "LESSON_CLOSE",
          "REFUND_READ",
          "REFUND_APPLY"
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
    return enrollment(mode, true);
  }

  EnrollmentView enrollment(int mode, boolean allocate) throws Exception {
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
    if (!allocate) return approved;
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
    return consume(enrollment, hours, new AttendanceInput(enrollment.id(), "PRESENT"));
  }

  String consumePartial(EnrollmentView enrollment, String hours, int attendedMinutes)
      throws Exception {
    return consume(
        enrollment, hours, new AttendanceInput(enrollment.id(), "PARTIAL", attendedMinutes));
  }

  private String consume(EnrollmentView enrollment, String hours, AttendanceInput attendance)
      throws Exception {
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
                    new ConfirmAttendance(published.version(), List.of(attendance))));
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
  void refundRequestFingerprintIncludesAmountCourseAndApproval() throws Exception {
    var enrollment = enrollment(4);
    var requestId = UUID.randomUUID();
    var initial = new CancelHoursAndReturnCommand(requestId, "1.00", "防重摘要", null, null, "8.00");
    var result = asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), initial));
    assertThat(asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), initial)))
        .isEqualTo(result);
    for (var changed :
        List.of(
            new CancelHoursAndReturnCommand(requestId, "1.00", "防重摘要", null, null, "9.00"),
            new CancelHoursAndReturnCommand(requestId, "1.00", "防重摘要", null, CHINESE, "8.00"),
            new CancelHoursAndReturnCommand(
                requestId, "1.00", "防重摘要", UUID.randomUUID(), null, "8.00")))
      assertThatThrownBy(
              () ->
                  asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), changed)))
          .isInstanceOf(FundsConflict.class)
          .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> modeRefundExpectations() {
    return Stream.of(
        // reconciliation returns cumulative consumed amount: first and second lessons combined.
        org.junit.jupiter.params.provider.Arguments.of(0, "10.00", "20.00"),
        org.junit.jupiter.params.provider.Arguments.of(1, "8.00", "16.00"),
        org.junit.jupiter.params.provider.Arguments.of(2, "10.00", "20.00"),
        org.junit.jupiter.params.provider.Arguments.of(3, "10.00", "20.00"),
        org.junit.jupiter.params.provider.Arguments.of(4, "8.00", "15.78"),
        org.junit.jupiter.params.provider.Arguments.of(5, "10.00", "20.00"));
  }

  @ParameterizedTest
  @MethodSource("modeRefundExpectations")
  void identicalMultiCourseDiscountAndPartialRefundScenarioMatchesModeCase(
      int mode, String expectedBeforeRefund, String expectedAfterRefund) throws Exception {
    lessonSeq = 0;
    classes.clear();
    var enrollment = enrollmentWithTwoCourses(mode);
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "200.00", "同组模式算例")));
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(),
                        SCHOOL,
                        enrollment.studentId(),
                        "800.00",
                        "CASH",
                        "2026-02-10")));
    asActor(
        OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "800.00")));
    assertThat(consume(enrollment, "1.00")).isEqualTo(expectedBeforeRefund);
    var command =
        new CancelHoursAndReturnCommand(
            UUID.randomUUID(),
            "10.00",
            "同组模式算例部分退课",
            null,
            mode == 1 || mode == 4 ? null : CHINESE,
            null);
    var refund = asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), command));
    assertThat(refund.amount()).isEqualTo("100.00");
    assertThat(refund.netAmount()).isEqualTo("700.00");
    assertThat(refund.hours()).isEqualTo("10.00");
    assertThat(refund.allocated()).isEqualTo("700.00");
    assertThat(refund.balanceTotal()).isEqualTo("100.00");
    assertThat(refund.balanceAvailable()).isEqualTo("100.00");
    // 报名创建日为 2026-02-10，当前财务日期在 2026-11；同一请求跨月重放不得重复退费。
    assertThat(asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), command)))
        .isEqualTo(refund);
    assertThat(consume(enrollment, "1.00")).isEqualTo(expectedAfterRefund);
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

  @Test
  void modeFourDeductsDiscountBeforeUnitPrice() throws Exception {
    var enrollment = enrollment(4, false);
    // 1000 元 100 课时，先优惠 200 元：应收 800 元，100 课时 → 8.00 元/课时。
    var discounted =
        asActor(
            OPS,
            () ->
                enrollments.applyDiscount(
                    enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "200.00", "开业优惠")));
    assertThat(discounted.netAmount()).isEqualTo("800.00");
    assertThat(consume(enrollment, "1.00")).isEqualTo("8.00");
  }

  @Test
  void modeFourDeductsDiscountAndRefundTogether() throws Exception {
    var enrollment = enrollment(4, false);
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "200.00", "开业优惠")));
    // 优惠后 800 元 100 课时，再退 50 课时退 200 元 → 剩余 600 元 / 50 课时 = 12.00。
    refund(enrollment, "50.00", "200.00");
    assertThat(consume(enrollment, "1.00")).isEqualTo("12.00");
  }

  @Test
  void modeFourTrailingAmountMatchesNetAfterDiscount() throws Exception {
    var enrollment = enrollment(4, false);
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "300.00", "开业优惠")));
    // 700 元 / 100 课时，最后一段一次上完必须正好补齐到 700.00。
    assertThat(consume(enrollment, "100.00")).isEqualTo("700.00");
  }

  @Test
  void combinedDiscountConsumptionRefundAndRetryReconcilesUnderCurrentModeFour() throws Exception {
    var enrollment = enrollmentWithTwoCourses(4);
    var discount = new ApplyDiscount(UUID.randomUUID(), "200.00", "组合算例优惠");
    asActor(OPS, () -> enrollments.applyDiscount(enrollment.id(), discount));
    asActor(OPS, () -> enrollments.applyDiscount(enrollment.id(), discount));
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(),
                        SCHOOL,
                        enrollment.studentId(),
                        "800",
                        "CASH",
                        "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "800")));
    // 人工预期：首课实际出勤 30/60 分钟，消费 0.50 小时 / 4.00 元；
    // 再退 10 小时、200 元后，总价 600 / 净课时 90 = 6.67 元/课时。
    assertThat(consumePartial(enrollment, "1.00", 30)).isEqualTo("4.00");
    var request =
        new CancelHoursAndReturnCommand(UUID.randomUUID(), "10.00", "组合算例退课", null, null, "200.00");
    var result = asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), request));
    assertThat(asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), request)))
        .isEqualTo(result);
    assertThat(result.amount()).isEqualTo("200.00");
    assertThat(result.netAmount()).isEqualTo("600.00");
    assertThat(result.allocated()).isEqualTo("600.00");
    assertThat(consume(enrollment, "1.00")).isEqualTo("10.67");
    var reconciliation = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(reconciliation.consumedHours()).isEqualTo("1.50");
    assertThat(reconciliation.cancelledHours()).isEqualTo("10.00");
    assertThat(reconciliation.remainingHours()).isEqualTo("88.50");
    assertThat(reconciliation.consumedAmount()).isEqualTo("10.67");
    var balance = asActor(OPS, () -> fundsQueries.balance(enrollment.studentId()));
    assertThat(balance.total()).isEqualTo("200.00");
    assertThat(balance.reserved()).isEqualTo("0.00");
    assertThat(balance.available()).isEqualTo("200.00");
    assertThat(balance.allocated()).isEqualTo("600.00");
  }

  @Test
  void modeFiveIgnoresDiscountLikeLegacy() throws Exception {
    // 旧系统模式 5 只减已退费用、不减优惠：语文 500 元 50 课时，优惠 200 后仍是 10.00 元/课时。
    // 这条行为有"消耗可能超过折后应收"的疑点，登记为 Q35；确认前先与旧系统保持一致。
    var enrollment = enrollmentWithTwoCourses(5);
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "200.00", "开业优惠")));
    assertThat(consume(enrollment, "1.00")).isEqualTo("10.00");
  }

  @Test
  void serviceFeeRefundReturnsOnlyFeeBalanceAndIsIdempotent() throws Exception {
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
                        "100.00",
                        1,
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
        "SF" + student.toString().replace("-", "").substring(0, 8),
        "服务费退费学员",
        "FEMALE",
        SCHOOL);
    var draft =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, price.id(), "2026-02-10")));
    var enrollment =
        asActor(OPS, () -> enrollments.approve(draft.id(), new EnrollmentVersion(draft.version())));
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(), SCHOOL, student, "1100", "CASH", "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1100")));

    var command =
        new RefundServiceFeeCommand(UUID.randomUUID(), "100.00", "25.00", "服务费退费附带25元额外消费", null);
    var result = asActor(OPS, () -> funds.refundServiceFee(enrollment.id(), command));
    assertThat(asActor(OPS, () -> funds.refundServiceFee(enrollment.id(), command)))
        .isEqualTo(result);
    assertThat(result.amount()).isEqualTo("100.00");
    assertThat(result.serviceFeeRefunded()).isEqualTo("100.00");
    assertThat(result.netAmount()).isEqualTo("1025.00");
    assertThat(result.allocated()).isEqualTo("1025.00");
    assertThat(result.balanceTotal()).isEqualTo("75.00");
    assertThat(result.balanceAvailable()).isEqualTo("75.00");
    var reversalCommand =
        new ReverseServiceFeeRefundCommand(UUID.randomUUID(), result.refundId(), "误操作冲正", null);
    var reversed =
        asActor(OPS, () -> funds.reverseServiceFeeRefund(enrollment.id(), reversalCommand));
    assertThat(reversed.serviceFeeRefunded()).isEqualTo("0.00");
    assertThat(reversed.netAmount()).isEqualTo("1100.00");
    assertThat(reversed.allocated()).isEqualTo("1100.00");
    assertThat(reversed.balanceTotal()).isEqualTo("0.00");
    assertThat(asActor(OPS, () -> funds.reverseServiceFeeRefund(enrollment.id(), reversalCommand)))
        .isEqualTo(reversed);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.reverseServiceFeeRefund(
                            enrollment.id(),
                            new ReverseServiceFeeRefundCommand(
                                UUID.randomUUID(), result.refundId(), "再次冲正", null))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("SERVICE_FEE_REFUND_ALREADY_REVERSED");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.refundServiceFee(
                            enrollment.id(),
                            new RefundServiceFeeCommand(
                                UUID.randomUUID(), "100.01", "超出服务费", null))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("SERVICE_FEE_REFUND_EXCEEDS_REMAINING");
  }

  /** 两门课程的报名：语文 + 数学各 50 课时 500 元。 */
  EnrollmentView enrollmentWithTwoCourses(int mode) throws Exception {
    var math = UUID.randomUUID();
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,?)",
        math,
        TENANT,
        SCHOOL,
        "数学" + math.toString().substring(0, 8));
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
        "S9" + student.toString().replace("-", "").substring(0, 6),
        "双课学员",
        "FEMALE",
        SCHOOL);
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(
                        UUID.randomUUID(),
                        student,
                        price.id(),
                        "2026-02-10",
                        List.of(
                            new CourseLine(CHINESE, "50.00", "500.00"),
                            new CourseLine(math, "50.00", "500.00")))));
    return asActor(
        OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
  }
}
