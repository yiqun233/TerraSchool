package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.approval.api.ApprovalCommands;
import org.teraschool.approval.api.CreateApprovalRequest;
import org.teraschool.approval.api.DecideApproval;
import org.teraschool.enrollment.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.product.api.*;
import org.teraschool.teaching.api.*;

/**
 * 结算模式 2/3 的课程匹配计价：按课程单价、按课程或总课时封顶、完全匹配模式补齐尾差。
 *
 * <p>金标准取自旧客户端帮助文本的例子：语文 1 课时 200 元 + 数学 3 课时 1800 元 —— 模式 1 每课时 500 元； 模式 2/3 语文 200 元/课时、数学 600
 * 元/课时。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementModePricingTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CHINESE = UUID.randomUUID(),
      MATH = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      APPROVER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired FundsCommands funds;
  @Autowired FundsQueries fundsQueries;
  @Autowired ApprovalCommands approvalCommands;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "计价机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','计价学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    EngineeringTest.seedExplicitApprovalPolicy(TENANT, SCHOOL);
    for (var action : List.of("ENROLLMENT_REDUCTION", "SERVICE_FEE_REDUCTION"))
      update(
          "insert into approval.policy(tenant_id,school_id,action,free_below,active)"
              + " values (?,?,?,999999999999.99,true)",
          TENANT,
          SCHOOL,
          action);
    for (var entry : Map.of(CHINESE, "语文", MATH, "数学").entrySet())
      update(
          "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          SCHOOL,
          entry.getValue());
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'多课程产品',1)",
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
        "pricing-ops",
        encoder.encode(PASSWORD));
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        APPROVER,
        TENANT,
        "pricing-approver",
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
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'APPROVAL_DECIDE','ALLOW'),(?,?,?,'APPROVAL_READ','ALLOW')",
        TENANT,
        APPROVER,
        SCHOOL,
        TENANT,
        APPROVER,
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

  /** 语文 1 课时 200 元 + 数学 3 课时 1800 元，总 4 课时 2000 元。 */
  EnrollmentView multiCourse(int mode) throws Exception {
    var price =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        PRODUCT,
                        "2000.00",
                        "4.00",
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
        "S7" + student.toString().replace("-", "").substring(0, 6),
        "计价学员" + student.toString().substring(0, 4),
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
                            new CourseLine(CHINESE, "1.00", "200.00"),
                            new CourseLine(MATH, "3.00", "1800.00")))));
    var approved =
        asActor(
            OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
    assertThat(approved.courses()).hasSize(2);
    return approved;
  }

  @Test
  void multiProductEnrollmentCreatesAndReplaysAllProductsTogether() throws Exception {
    var productTwo =
        asActor(
            OPS,
            () ->
                catalog.createProduct(
                    new CreateProduct(UUID.randomUUID(), SCHOOL, CHINESE, "第二个产品", 1)));
    var draftOne =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        PRODUCT,
                        "2000.00",
                        "4.00",
                        "0.00",
                        1,
                        null,
                        "2026-01-01",
                        null)));
    var draftTwo =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        productTwo.id(),
                        "500.00",
                        "1.00",
                        "0.00",
                        1,
                        null,
                        "2026-01-01",
                        null)));
    var priceOne =
        asActor(
            OPS,
            () ->
                catalog.publishPriceVersion(
                    draftOne.id(), new ExpectedVersion(draftOne.version())));
    var priceTwo =
        asActor(
            OPS,
            () ->
                catalog.publishPriceVersion(
                    draftTwo.id(), new ExpectedVersion(draftTwo.version())));
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "M9" + student.toString().replace("-", "").substring(0, 6),
        "多产品学员",
        "FEMALE",
        SCHOOL);
    var command =
        new CreateEnrollmentBatch(
            List.of(
                new CreateEnrollment(UUID.randomUUID(), student, priceOne.id(), "2026-02-10"),
                new CreateEnrollment(UUID.randomUUID(), student, priceTwo.id(), "2026-02-10")));

    var created = asActor(OPS, () -> enrollments.createBatch(command));
    var replay = asActor(OPS, () -> enrollments.createBatch(command));

    assertThat(created.items()).hasSize(2);
    assertThat(created.items())
        .extracting(EnrollmentView::productId)
        .containsExactly(PRODUCT, productTwo.id());
    assertThat(replay.items())
        .extracting(EnrollmentView::id)
        .containsExactlyElementsOf(created.items().stream().map(EnrollmentView::id).toList());
  }

  @Test
  void invalidLaterProductRollsBackEarlierBatchEnrollments() throws Exception {
    var productOne =
        asActor(
            OPS,
            () ->
                catalog.createProduct(
                    new CreateProduct(UUID.randomUUID(), SCHOOL, CHINESE, "可售产品", 1)));
    var productTwo =
        asActor(
            OPS,
            () ->
                catalog.createProduct(
                    new CreateProduct(UUID.randomUUID(), SCHOOL, MATH, "未生效产品", 1)));
    var draftOne =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        productOne.id(),
                        "1000.00",
                        "10.00",
                        "0.00",
                        1,
                        null,
                        "2026-01-01",
                        null)));
    var draftTwo =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        productTwo.id(),
                        "500.00",
                        "5.00",
                        "0.00",
                        1,
                        null,
                        "2027-01-01",
                        null)));
    var priceOne =
        asActor(
            OPS,
            () ->
                catalog.publishPriceVersion(
                    draftOne.id(), new ExpectedVersion(draftOne.version())));
    var priceTwo =
        asActor(
            OPS,
            () ->
                catalog.publishPriceVersion(
                    draftTwo.id(), new ExpectedVersion(draftTwo.version())));
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "R8" + student.toString().replace("-", "").substring(0, 6),
        "回滚学员",
        "FEMALE",
        SCHOOL);
    var command =
        new CreateEnrollmentBatch(
            List.of(
                new CreateEnrollment(UUID.randomUUID(), student, priceOne.id(), "2026-02-10"),
                new CreateEnrollment(UUID.randomUUID(), student, priceTwo.id(), "2026-02-10")));

    assertThatThrownBy(() -> asActor(OPS, () -> enrollments.createBatch(command)))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("PRICE_VERSION_NOT_SELLABLE");
    assertThat(
            asActor(OPS, () -> enrollmentQueries.list(SCHOOL, "", 0, 100)).items().stream()
                .filter(item -> student.equals(item.studentId())))
        .isEmpty();
  }

  /** 每个课程建一个班并在首次使用时就加入成员；之后每节课都上在这个班里。 */
  private final Map<UUID, UUID> classes = new HashMap<>();

  private final Map<UUID, Integer> lessonSeq = new HashMap<>();

  UUID classFor(EnrollmentView enrollment, UUID curriculum) throws Exception {
    var existing = classes.get(curriculum);
    if (existing != null) return existing;
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, curriculum, "班" + UUID.randomUUID())));
    asActor(OPS, () -> teaching.addMember(classView.id(), new AddClassMember(enrollment.id())));
    classes.put(curriculum, classView.id());
    return classView.id();
  }

  /** 在该课程的班里上一节课并关闭，返回累计消费金额。 */
  String consume(EnrollmentView enrollment, UUID curriculum, String hours) throws Exception {
    var classId = classFor(enrollment, curriculum);
    var seq = lessonSeq.merge(curriculum, 1, Integer::sum);
    var day = String.format("2026-03-%02d", seq + 1);
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

  @Test
  void modeOneUsesTotalAverage() throws Exception {
    var enrollment = multiCourse(1);
    // 模式 1 下即使课次属于语文，也按总单价 500 元/课时计价。
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("500.00");
  }

  @Test
  void modeTwoUsesCourseAverage() throws Exception {
    var enrollment = multiCourse(2);
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("200.00");
    assertThat(consume(enrollment, MATH, "1.00")).isEqualTo("800.00");
  }

  @Test
  void modeTwoUsesEnrollmentTotalAsCap() throws Exception {
    var enrollment = multiCourse(2);
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("200.00");
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("400.00");
  }

  @Test
  void lessonCrossingRemainingHoursIsRejected() throws Exception {
    var enrollment = multiCourse(1);
    assertThatThrownBy(() -> consume(enrollment, CHINESE, "4.50"))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("LESSON_HOURS_EXCEED_REMAINING");
    assertThat(asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id())).consumedHours())
        .isEqualTo("0.00");
  }

  @Test
  void discountReplayIsIdempotentAndChangedParametersAreRejected() throws Exception {
    var enrollment = multiCourse(1);
    var id = UUID.randomUUID();
    var discount = new ApplyDiscount(id, "400.00", "优惠", enrollment.version(), null);
    asActor(OPS, () -> enrollments.applyDiscount(enrollment.id(), discount));
    var replay = asActor(OPS, () -> enrollments.applyDiscount(enrollment.id(), discount));
    assertThat(replay.netAmount()).isEqualTo("1600.00");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        enrollments.applyDiscount(
                            enrollment.id(), new ApplyDiscount(id, "500.00", "优惠"))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("400.00");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        enrollments.applyDiscount(
                            enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "100.00", "优惠"))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("DISCOUNT_AFTER_CONSUMPTION");
  }

  @Test
  void discountApprovalFailureRollsBackAllocationRebuild() throws Exception {
    var enrollment = multiCourse(1);
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(),
                        SCHOOL,
                        enrollment.studentId(),
                        "2000",
                        "CASH",
                        "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "2000")));
    update(
        "update approval.policy set free_below=0 where school_id=? and action='ENROLLMENT_DISCOUNT'",
        SCHOOL);
    try {
      assertThatThrownBy(
              () ->
                  asActor(
                      OPS,
                      () ->
                          enrollments.applyDiscount(
                              enrollment.id(),
                              new ApplyDiscount(UUID.randomUUID(), "400.00", "需审批"))))
          .hasMessage("APPROVAL_REQUIRED");
    } finally {
      update(
          "update approval.policy set free_below=999999999999.99 where school_id=? and action='ENROLLMENT_DISCOUNT'",
          SCHOOL);
    }
    assertThat(asActor(OPS, () -> enrollmentQueries.detail(enrollment.id())).netAmount())
        .isEqualTo("2000.00");
    assertThat(asActor(OPS, () -> fundsQueries.allocations(enrollment.id())))
        .filteredOn(allocation -> "ACTIVE".equals(allocation.status()))
        .extracting(allocation -> allocation.amount())
        .containsExactly("2000.00");
    assertThat(asActor(OPS, () -> fundsQueries.balance(enrollment.studentId())).available())
        .isEqualTo("0.00");
  }

  @Test
  void approvedDiscountConsumesItsApprovalOnce() throws Exception {
    var enrollment = multiCourse(1);
    update(
        "update approval.policy set free_below=0 where school_id=? and action='ENROLLMENT_DISCOUNT'",
        SCHOOL);
    try {
      var request =
          asActor(
              OPS,
              () ->
                  approvalCommands.request(
                      new CreateApprovalRequest(
                          UUID.randomUUID(),
                          enrollment.id(),
                          "ENROLLMENT_DISCOUNT",
                          "400.00",
                          "核准减免")));
      var approved =
          asActor(
              APPROVER,
              () ->
                  approvalCommands.decide(
                      request.id(), new DecideApproval(request.version(), "APPROVED", "复核通过")));
      asActor(
          OPS,
          () ->
              enrollments.applyDiscount(
                  enrollment.id(),
                  new ApplyDiscount(
                      UUID.randomUUID(), "400.00", "核准减免", enrollment.version(), approved.id())));

      assertThatThrownBy(
              () ->
                  asActor(
                      OPS,
                      () ->
                          enrollments.applyDiscount(
                              enrollment.id(),
                              new ApplyDiscount(
                                  UUID.randomUUID(), "400.00", "重复使用审批", null, approved.id()))))
          .hasMessage("APPROVAL_ALREADY_USED");
      assertThat(asActor(OPS, () -> enrollmentQueries.detail(enrollment.id())).netAmount())
          .isEqualTo("1600.00");
    } finally {
      update(
          "update approval.policy set free_below=999999999999.99 where school_id=? and action='ENROLLMENT_DISCOUNT'",
          SCHOOL);
    }
  }

  @Test
  void approvedEnrollmentReductionIsVersionedAndRequestIdempotent() throws Exception {
    var enrollment = multiCourse(1);
    update(
        "update approval.policy set free_below=0 where school_id=? and action='ENROLLMENT_REDUCTION'",
        SCHOOL);
    try {
      var request =
          asActor(
              OPS,
              () ->
                  approvalCommands.request(
                      new CreateApprovalRequest(
                          UUID.randomUUID(),
                          enrollment.id(),
                          "ENROLLMENT_REDUCTION",
                          "400.00",
                          "批准报名减款")));
      var approved =
          asActor(
              APPROVER,
              () ->
                  approvalCommands.decide(
                      request.id(), new DecideApproval(request.version(), "APPROVED", "同意")));
      var command =
          new ApplyReduction(
              UUID.randomUUID(),
              "ENROLLMENT_REDUCTION",
              "400.00",
              "批准报名减款",
              enrollment.version(),
              approved.id());
      var reduced = asActor(OPS, () -> enrollments.applyReduction(enrollment.id(), command));
      assertThat(reduced.netAmount()).isEqualTo("1600.00");
      assertThat(asActor(OPS, () -> enrollments.applyReduction(enrollment.id(), command)))
          .isEqualTo(reduced);
    } finally {
      update(
          "update approval.policy set free_below=999999999999.99 where school_id=? and action='ENROLLMENT_REDUCTION'",
          SCHOOL);
    }
  }

  @Test
  void serviceFeeReductionUsesItsOwnApprovalAndCannotExceedFeeBalance() throws Exception {
    var enrollment = multiCourse(1);
    update("update enrollment.enrollment set service_fee=100.00 where id=?", enrollment.id());
    update(
        "update approval.policy set free_below=0 where school_id=? and action='SERVICE_FEE_REDUCTION'",
        SCHOOL);
    try {
      var approvalRequest =
          asActor(
              OPS,
              () ->
                  approvalCommands.request(
                      new CreateApprovalRequest(
                          UUID.randomUUID(),
                          enrollment.id(),
                          "SERVICE_FEE_REDUCTION",
                          "100.00",
                          "服务费首期减款")));
      var approved =
          asActor(
              APPROVER,
              () ->
                  approvalCommands.decide(
                      approvalRequest.id(),
                      new DecideApproval(approvalRequest.version(), "APPROVED", "同意")));
      var reduced =
          asActor(
              OPS,
              () ->
                  enrollments.applyReduction(
                      enrollment.id(),
                      new ApplyReduction(
                          UUID.randomUUID(),
                          "SERVICE_FEE_REDUCTION",
                          "100.00",
                          "服务费首期减款",
                          enrollment.version(),
                          approved.id())));
      assertThat(reduced.netAmount()).isEqualTo("1900.00");

      var overLimitRequest =
          asActor(
              OPS,
              () ->
                  approvalCommands.request(
                      new CreateApprovalRequest(
                          UUID.randomUUID(),
                          enrollment.id(),
                          "SERVICE_FEE_REDUCTION",
                          "0.01",
                          "验证服务费余额上限")));
      var overLimitApproved =
          asActor(
              APPROVER,
              () ->
                  approvalCommands.decide(
                      overLimitRequest.id(),
                      new DecideApproval(overLimitRequest.version(), "APPROVED", "同意")));
      assertThatThrownBy(
              () ->
                  asActor(
                      OPS,
                      () ->
                          enrollments.applyReduction(
                              enrollment.id(),
                              new ApplyReduction(
                                  UUID.randomUUID(),
                                  "SERVICE_FEE_REDUCTION",
                                  "0.01",
                                  "超额验证",
                                  reduced.version(),
                                  overLimitApproved.id()))))
          .isInstanceOf(EnrollmentConflict.class)
          .hasMessage("SERVICE_FEE_REDUCTION_EXCEEDS_REMAINING");
    } finally {
      update(
          "update approval.policy set free_below=999999999999.99 where school_id=? and action='SERVICE_FEE_REDUCTION'",
          SCHOOL);
    }
  }

  @Test
  void concurrentReplayOfSameDiscountRequestCreatesOneVersion() throws Exception {
    var enrollment = multiCourse(1);
    var command =
        new ApplyDiscount(UUID.randomUUID(), "400.00", "并发重放", enrollment.version(), null);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () -> {
                start.await();
                return asActor(OPS, () -> enrollments.applyDiscount(enrollment.id(), command));
              });
      var second =
          workers.submit(
              () -> {
                start.await();
                return asActor(OPS, () -> enrollments.applyDiscount(enrollment.id(), command));
              });
      start.countDown();
      assertThat(first.get().netAmount()).isEqualTo("1600.00");
      assertThat(second.get().netAmount()).isEqualTo("1600.00");
    }
    assertThat(asActor(OPS, () -> enrollmentQueries.detail(enrollment.id())).netAmount())
        .isEqualTo("1600.00");
  }

  @Test
  void discountRebalancesExcessAllocationBackToStudentAccount() throws Exception {
    var enrollment = multiCourse(1);
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(),
                        SCHOOL,
                        enrollment.studentId(),
                        "2000",
                        "CASH",
                        "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "2000")));
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "400.00", "优惠")));
    assertThat(asActor(OPS, () -> enrollmentQueries.detail(enrollment.id())).netAmount())
        .isEqualTo("1600.00");
    assertThat(asActor(OPS, () -> fundsQueries.allocations(enrollment.id())))
        .filteredOn(allocation -> "ACTIVE".equals(allocation.status()))
        .extracting(allocation -> allocation.amount())
        .containsExactly("1600.00");
    assertThat(asActor(OPS, () -> fundsQueries.balance(enrollment.studentId())).available())
        .isEqualTo("400.00");
  }

  @Test
  void modeThreeCapsPerCourse() throws Exception {
    var enrollment = multiCourse(3);
    // 语文只有 1 课时：第一节 200 元，第二节不再消耗。
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("200.00");
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("200.00");
  }

  @Test
  void modeTwoIgnoresUnrelatedCurriculum() throws Exception {
    var enrollment = multiCourse(2);
    var other = UUID.randomUUID();
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'美术')",
        other,
        TENANT,
        SCHOOL);
    var otherClass =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, other, "历史错课班" + UUID.randomUUID())));
    // 当前普通入班入口已拒绝课程不匹配；不能为了构造计价样本绕过这项生产保护。
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () -> teaching.addMember(otherClass.id(), new AddClassMember(enrollment.id()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("ENROLLMENT_COURSE_MISMATCH");
    // 管理连接只构造历史异常关系，继续验证计价层不会对不在报名明细中的课程收钱。
    update(
        "insert into teaching.enrollment_class(tenant_id,enrollment_id,class_id,joined_on)"
            + " values (?,?,?,'2026-02-10')",
        TENANT,
        enrollment.id(),
        otherClass.id());
    classes.put(other, otherClass.id());
    assertThat(consume(enrollment, other, "1.00")).isEqualTo("0.00");
  }

  @Test
  void refundOfModeTwoNeedsCourseAndUsesCourseUnitPrice() throws Exception {
    var enrollment = multiCourse(2);
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(),
                        SCHOOL,
                        enrollment.studentId(),
                        "2000",
                        "CASH",
                        "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "2000")));
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.cancelHoursAndReturnToAccount(
                            enrollment.id(),
                            new CancelHoursAndReturnCommand(
                                UUID.randomUUID(), "1.00", "退语文", null, null))))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("CURRICULUM_REQUIRED");
    var refunded =
        asActor(
            OPS,
            () ->
                funds.cancelHoursAndReturnToAccount(
                    enrollment.id(),
                    new CancelHoursAndReturnCommand(
                        UUID.randomUUID(), "1.00", "退语文", null, CHINESE)));
    assertThat(refunded.amount()).isEqualTo("200.00");
    assertThat(refunded.netAmount()).isEqualTo("1800.00");
  }

  /** 数学 3 课时 1000 元，除不尽：前三节按四舍五入，最后一节补齐尾差。 */
  EnrollmentView oddPriced(int mode) throws Exception {
    var price =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        PRODUCT,
                        "1200.00",
                        "4.00",
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
        "S7" + student.toString().replace("-", "").substring(0, 6),
        "尾差学员" + student.toString().substring(0, 4),
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
                            new CourseLine(CHINESE, "1.00", "200.00"),
                            new CourseLine(MATH, "3.00", "1000.00")))));
    return asActor(
        OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
  }

  @Test
  void modeThreeFixesRoundingOnLastCourseHour() throws Exception {
    var enrollment = oddPriced(3);
    // 1000 ÷ 3 = 333.333333：前两节各 333.33，最后一节补齐为 333.34，合计正好 1000.00。
    assertThat(consume(enrollment, MATH, "1.00")).isEqualTo("333.33");
    assertThat(consume(enrollment, MATH, "1.00")).isEqualTo("666.66");
    // 第三节补齐尾差：1000.00 − 666.66 = 333.34，累计正好等于课程费用。
    assertThat(consume(enrollment, MATH, "1.00")).isEqualTo("1000.00");
    var rec = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(rec.consumedHours()).isEqualTo("3.00");
    assertThat(rec.consumptions()).hasSize(3);
  }

  @Test
  void modeOneDeductsDiscountLikeLegacy() throws Exception {
    var enrollment = multiCourse(1);
    // 旧系统：fee = 总价 − 优惠。总价 2000 元 4 课时 → 优惠 400 后 1600 / 4 = 400 元/课时。
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "400.00", "开学优惠")));
    assertThat(consume(enrollment, CHINESE, "1.00")).isEqualTo("400.00");
  }

  @Test
  void modeOneTrailingFillsNetAfterDiscount() throws Exception {
    var enrollment = multiCourse(1);
    asActor(
        OPS,
        () ->
            enrollments.applyDiscount(
                enrollment.id(), new ApplyDiscount(UUID.randomUUID(), "400.00", "开学优惠")));
    // 4 课时一次上完：正好补齐到 1600.00。
    assertThat(consume(enrollment, CHINESE, "4.00")).isEqualTo("1600.00");
  }
}
