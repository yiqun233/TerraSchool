package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.enrollment.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.product.api.*;

/** 结算模式取值防护：0–5 全部可用，越界值在起草阶段就拒绝；模式 0 未解析前不能退费。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementModeGuardTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
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
    EngineeringTest.seedExplicitApprovalPolicy(TENANT, SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'模式课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'模式产品',1)",
        PRODUCT,
        TENANT,
        SCHOOL,
        CURRICULUM);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        OPS,
        TENANT,
        "mode-ops",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action :
        new String[] {
          "PRODUCT_READ",
          "PRODUCT_MAINTAIN",
          "PRICE_PUBLISH",
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "FUNDS_READ",
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

  PriceVersion draft(int mode) {
    return asActor(
        OPS,
        () ->
            catalog.draftPriceVersion(
                new DraftPriceVersion(
                    UUID.randomUUID(),
                    PRODUCT,
                    "1000.00",
                    "10.00",
                    "0.00",
                    mode,
                    null,
                    "2026-01-01",
                    null)));
  }

  @Test
  void invalidModeIsRejectedAtDraft() {
    assertThatThrownBy(() -> draft(6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("结算模式");
  }

  @Test
  void everyModeZeroToFiveIsPublishable() {
    for (var mode = 0; mode <= 5; mode++) {
      var price = draft(mode);
      assertThat(
              asActor(
                      OPS,
                      () ->
                          catalog.publishPriceVersion(
                              price.id(), new ExpectedVersion(price.version())))
                  .status())
          .isEqualTo("PUBLISHED");
    }
  }

  @Test
  void refundBeforeAutoModeResolvedIsRejected() throws Exception {
    var price = draft(0);
    asActor(
        OPS, () -> catalog.publishPriceVersion(price.id(), new ExpectedVersion(price.version())));
    var student = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        student,
        TENANT,
        "S2" + student.toString().replace("-", "").substring(0, 6),
        "模式学员" + student.toString().substring(0, 4),
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
    assertThat(approved.settlementMode()).isZero();
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.cancelHoursAndReturnToAccount(
                            approved.id(),
                            new CancelHoursAndReturnCommand(
                                UUID.randomUUID(), "1.00", "退课", null))))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("SETTLEMENT_MODE_UNRESOLVED");
  }
}
