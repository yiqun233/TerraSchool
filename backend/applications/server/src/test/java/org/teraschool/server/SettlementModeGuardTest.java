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

/** 结算模式防护：只实现模式 1，其余模式必须在发布、消费与退费处显式拒绝，不得按模式 1 静默算钱。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementModeGuardTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired TeachingCommands teaching;
  @Autowired FundsCommands funds;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "模式机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','模式学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'模式课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        OPS,
        TENANT,
        "mode-ops",
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

  CourseProduct product() {
    return asActor(
        OPS,
        () ->
            catalog.createProduct(
                new CreateProduct(
                    UUID.randomUUID(), SCHOOL, CURRICULUM, "模式产品" + UUID.randomUUID(), 1)));
  }

  PriceVersion draft(UUID productId, int mode) {
    return asActor(
        OPS,
        () ->
            catalog.draftPriceVersion(
                new DraftPriceVersion(
                    UUID.randomUUID(),
                    productId,
                    "1000.00",
                    "10.00",
                    "0.00",
                    mode,
                    null,
                    "2026-01-01",
                    null)));
  }

  UUID legacyEnrollment(int mode) throws Exception {
    var product = product();
    var priceId = UUID.randomUUID();
    try {
      update(
          "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,"
              + " service_fee,settlement_mode,effective_from)"
              + " values (?,?,?,1,'PUBLISHED',1000.00,10.00,0,?,'2026-01-01')",
          priceId,
          TENANT,
          product.id(),
          mode);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "S9" + student.toString().replace("-", "").substring(0, 6),
        "模式学员" + student.toString().substring(0, 4),
        "FEMALE",
        SCHOOL);
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, priceId, "2026-02-10")));
    var approved =
        asActor(
            OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
    assertThat(approved.settlementMode()).isEqualTo(mode);
    return approved.id();
  }

  @Test
  void publishingUnimplementedModeIsRejected() throws Exception {
    var product = product();
    // 模式 4/5 需要退课后重算，尚未实现：必须拒绝发布。
    var modeFour = draft(product.id(), 4);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        catalog.publishPriceVersion(
                            modeFour.id(), new ExpectedVersion(modeFour.version()))))
        .isInstanceOf(ProductConflict.class)
        .hasMessage("SETTLEMENT_MODE_UNSUPPORTED");
    // 模式 2/3 已实现，可以发布。
    var modeTwo = draft(product.id(), 2);
    assertThat(
            asActor(
                    OPS,
                    () ->
                        catalog.publishPriceVersion(
                            modeTwo.id(), new ExpectedVersion(modeTwo.version())))
                .status())
        .isEqualTo("PUBLISHED");
    var modeOne = draft(product.id(), 1);
    assertThat(
            asActor(
                    OPS,
                    () ->
                        catalog.publishPriceVersion(
                            modeOne.id(), new ExpectedVersion(modeOne.version())))
                .status())
        .isEqualTo("PUBLISHED");
  }

  @Test
  void closingLessonOnLegacyModeIsRejected() throws Exception {
    var enrollmentId = legacyEnrollment(4);
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "模式班" + UUID.randomUUID())));
    asActor(OPS, () -> teaching.addMember(classView.id(), new AddClassMember(enrollmentId)));
    var lesson =
        asActor(
            OPS,
            () ->
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classView.id(),
                        "2026-03-02",
                        "09:00",
                        "10:00",
                        null,
                        null,
                        "课",
                        "1.00")));
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
                        List.of(new AttendanceInput(enrollmentId, "PRESENT")))));
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        teaching.closeLesson(
                            confirmed.id(), new LessonVersion(confirmed.version()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("SETTLEMENT_MODE_UNSUPPORTED");
  }

  @Test
  void refundingLegacyModeIsRejected() throws Exception {
    var enrollmentId = legacyEnrollment(5);
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "S9" + student.toString().replace("-", "").substring(0, 6),
        "模式学员" + student.toString().substring(0, 4),
        "FEMALE",
        SCHOOL);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.cancelHoursAndReturnToAccount(
                            enrollmentId,
                            new CancelHoursAndReturnCommand(
                                UUID.randomUUID(), "2.00", "退课时", null))))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("SETTLEMENT_MODE_UNSUPPORTED");
  }
}
