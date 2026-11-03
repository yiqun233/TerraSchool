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
import org.teraschool.product.api.*;
import org.teraschool.teaching.api.*;

/** 期初已消耗课时：无课次、计入消费汇总、影响可退课时，同一报名只能记一次。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningConsumptionTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "期初课时机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','期初课时学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'期初课时课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'期初课时产品',1)",
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
        "opening-hours-ops",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action :
        new String[] {
          "PRODUCT_READ",
          "PRODUCT_MAINTAIN",
          "PRICE_PUBLISH",
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "TEACHING_READ",
          "TEACHING_MAINTAIN",
          "MIGRATION_MANAGE"
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

  @Test
  void teachingMaintenanceAloneCannotPostOpening() throws Exception {
    var reader = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        reader,
        TENANT,
        "opening-teacher",
        "unused");
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'TEACHING_MAINTAIN','ALLOW')",
        TENANT,
        reader,
        SCHOOL);
    var enrollment = enrollment();
    assertThatThrownBy(
            () ->
                asActor(
                    reader,
                    () -> {
                      teaching.openingConsumption(
                          enrollment.id(),
                          new OpeningConsumption("1.00", "100.00", "旧库迁移", "2026-02-10"));
                      return null;
                    }))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  EnrollmentView enrollment() throws Exception {
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
                        "0.00",
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
        "P" + student.toString().replace("-", "").substring(0, 6),
        "期初课时学员",
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

  @Test
  void openingConsumptionCountsTowardsConsumedAndRemaining() throws Exception {
    var enrollment = enrollment();
    asActor(
        OPS,
        () -> {
          teaching.openingConsumption(
              enrollment.id(), new OpeningConsumption("3.50", "350.00", "旧库已消耗", "2026-02-10"));
          return null;
        });
    var reconciliation = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(reconciliation.consumedHours()).isEqualTo("3.50");
    assertThat(reconciliation.consumedAmount()).isEqualTo("350.00");
    assertThat(reconciliation.remainingHours()).isEqualTo("6.50");
  }

  @Test
  void secondOpeningForSameEnrollmentIsRejected() throws Exception {
    var enrollment = enrollment();
    asActor(
        OPS,
        () -> {
          teaching.openingConsumption(
              enrollment.id(), new OpeningConsumption("2.00", "200.00", "第一次", "2026-02-10"));
          return null;
        });
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () -> {
                      teaching.openingConsumption(
                          enrollment.id(),
                          new OpeningConsumption("1.00", "100.00", "第二次", "2026-02-10"));
                      return null;
                    }))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("OPENING_ALREADY_RECORDED");
  }

  @Test
  void openingCannotExceedPurchasedHours() throws Exception {
    var enrollment = enrollment();
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () -> {
                      teaching.openingConsumption(
                          enrollment.id(),
                          new OpeningConsumption("11.00", "1100.00", "超量", "2026-02-10"));
                      return null;
                    }))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("HOURS_EXCEED_PURCHASED");
  }

  @Test
  void openingConsumptionCannotBypassLockedPeriod() throws Exception {
    var enrollment = enrollment();
    update(
        "insert into finance.period(tenant_id,school_id,period,status) values (?,?,'2026-04','LOCKED')",
        TENANT,
        SCHOOL);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () -> {
                      teaching.openingConsumption(
                          enrollment.id(),
                          new OpeningConsumption("1.00", "100.00", "旧库", "2026-04-10"));
                      return null;
                    }))
        .isInstanceOf(org.teraschool.finance.api.PeriodConflict.class)
        .hasMessage("PERIOD_LOCKED");
    assertThat(asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id())).consumedHours())
        .isEqualTo("0.00");
  }

  @Test
  void zeroAmountIsAcceptedForLegacyDataWithoutAmounts() throws Exception {
    var enrollment = enrollment();
    asActor(
        OPS,
        () -> {
          teaching.openingConsumption(
              enrollment.id(), new OpeningConsumption("2.00", "0", "旧库只有课时", "2026-02-10"));
          return null;
        });
    var reconciliation = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(reconciliation.consumedHours()).isEqualTo("2.00");
    assertThat(reconciliation.consumedAmount()).isEqualTo("0.00");
  }
}
