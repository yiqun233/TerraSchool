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
import org.teraschool.product.api.*;
import org.teraschool.teaching.api.*;

/**
 * 结算模式 0（按班型自动解析）、服务费计入应收、一次性减免。
 *
 * <p>旧系统：模式 0 下 VIP 走平均价格(1)、大班走课程匹配(2)；服务费与价格分列；减免是一次性调整。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClassTypeAndPricingTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CHINESE = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @LocalServerPort int port;
  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired TeachingCommands teaching;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "班型机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','班型学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    EngineeringTest.seedExplicitApprovalPolicy(TENANT, SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'班型课程')",
        CHINESE,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'班型产品',0)",
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
        "classtype-ops",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action :
        new String[] {
          "PRODUCT_READ",
          "PRODUCT_MAINTAIN",
          "PRICE_PUBLISH",
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "ENROLLMENT_DISCOUNT",
          "TEACHING_READ",
          "TEACHING_MAINTAIN"
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

  /** 发布一个指定模式与服务费的价格，并登记一条报名。 */
  EnrollmentView enrollment(int mode, String serviceFee) throws Exception {
    var price =
        asActor(
            OPS,
            () ->
                catalog.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        PRODUCT,
                        "1000.00",
                        "10.00",
                        serviceFee,
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
        "S3" + student.toString().replace("-", "").substring(0, 6),
        "班型学员" + student.toString().substring(0, 4),
        "FEMALE",
        SCHOOL);
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, price.id(), "2026-02-10")));
    return asActor(
        OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
  }

  UUID classOf(String type) {
    return asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CHINESE, "班" + UUID.randomUUID(), type)))
        .id();
  }

  @Test
  void serviceFeeIsIncludedInReceivable() throws Exception {
    var created = enrollment(1, "100.00");
    assertThat(created.netAmount()).isEqualTo("1100.00");
  }

  @Test
  void modeZeroResolvesToAveragePriceForVipClass() throws Exception {
    var created = enrollment(0, "0.00");
    assertThat(created.settlementMode()).isZero();
    var classId = classOf("VIP");
    asActor(OPS, () -> teaching.addMember(classId, new AddClassMember(created.id())));
    var resolved = asActor(OPS, () -> enrollmentQueries.detail(created.id()));
    assertThat(resolved.settlementMode()).isEqualTo(1);
  }

  @Test
  void modeZeroResolvesToCourseMatchForLargeClass() throws Exception {
    var created = enrollment(0, "0.00");
    var classId = classOf("LARGE");
    asActor(OPS, () -> teaching.addMember(classId, new AddClassMember(created.id())));
    assertThat(asActor(OPS, () -> enrollmentQueries.detail(created.id())).settlementMode())
        .isEqualTo(2);
  }

  @Test
  void discountReducesReceivableAndCannotExceedIt() throws Exception {
    var created = enrollment(1, "0.00");
    var discounted =
        asActor(
            OPS,
            () ->
                enrollments.applyDiscount(
                    created.id(), new ApplyDiscount(UUID.randomUUID(), "200", "老生优惠")));
    assertThat(discounted.netAmount()).isEqualTo("800.00");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        enrollments.applyDiscount(
                            created.id(), new ApplyDiscount(UUID.randomUUID(), "900", "超额"))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("DISCOUNT_EXCEED_RECEIVABLE");
  }
}
