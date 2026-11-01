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
      OPS = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired FundsCommands funds;

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
    // 课次课程不在报名明细内：模式 2 不计消耗（金额保持 0）。
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
}
