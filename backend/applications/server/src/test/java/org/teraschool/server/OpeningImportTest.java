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
import org.teraschool.migration.api.*;
import org.teraschool.product.api.*;
import org.teraschool.teaching.api.*;

/** 期初批量导入：逐行结果、单行失败不影响其他行、按批次幂等、干跑不写库。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningImportTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired FundsQueries fundsQueries;
  @Autowired TeachingQueries teachingQueries;
  @Autowired LegacyMigration migration;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "期初导入机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','期初导入学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'期初导入课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'期初导入产品',1)",
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
        "opening-import-ops",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action :
        new String[] {
          "MIGRATION_MANAGE",
          "FUNDS_READ",
          "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE",
          "PERIOD_READ",
          "TEACHING_READ",
          "TEACHING_MAINTAIN",
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "PRODUCT_READ",
          "PRODUCT_MAINTAIN",
          "PRICE_PUBLISH"
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

  /** 建一个已有 10 课时报名的学员，返回学员号。 */
  String studentWithEnrollment(String suffix) throws Exception {
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
    var studentId = UUID.randomUUID();
    var no = "I" + suffix;
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        studentId,
        TENANT,
        no,
        "期初导入学员" + suffix,
        "FEMALE",
        SCHOOL);
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), studentId, price.id(), "2026-02-10")));
    asActor(OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
    return no;
  }

  OpeningImport request(String batchId, boolean dryRun, OpeningImport.Row... rows) {
    return new OpeningImport(batchId, "2026-02-10", "旧库迁移", List.of(rows), dryRun);
  }

  @Test
  void importsAmountAndHoursPerRow() throws Exception {
    var no = studentWithEnrollment("001");
    var report =
        asActor(
            OPS,
            () ->
                migration.importOpenings(
                    SCHOOL,
                    request("B1", false, new OpeningImport.Row(no, "500.00", "4.00", "400.00"))));
    assertThat(report.ok()).describedAs("rows=%s", report.rows()).isEqualTo(1);
    assertThat(report.failed()).isZero();
    assertThat(report.totalAmount()).isEqualTo("500.00");
    assertThat(report.totalHours()).isEqualTo("4.00");
    assertThat(report.rows())
        .singleElement()
        .satisfies(row -> assertThat(row.status()).isEqualTo("OK"));
  }

  @Test
  void missingStudentFailsOnlyThatRow() throws Exception {
    var no = studentWithEnrollment("002");
    var report =
        asActor(
            OPS,
            () ->
                migration.importOpenings(
                    SCHOOL,
                    request(
                        "B2",
                        false,
                        new OpeningImport.Row(no, "100.00", "0", "0"),
                        new OpeningImport.Row("NOPE-9999", "100.00", "0", "0"))));
    assertThat(report.ok()).isEqualTo(1);
    assertThat(report.failed()).isEqualTo(1);
    assertThat(report.rows())
        .anySatisfy(
            row -> {
              assertThat(row.studentNo()).isEqualTo("NOPE-9999");
              assertThat(row.status()).isEqualTo("FAILED");
            });
  }

  @Test
  void rerunSameBatchIsIdempotent() throws Exception {
    var no = studentWithEnrollment("003");
    asActor(
        OPS,
        () ->
            migration.importOpenings(
                SCHOOL, request("B3", false, new OpeningImport.Row(no, "300.00", "0", "0"))));
    var again =
        asActor(
            OPS,
            () ->
                migration.importOpenings(
                    SCHOOL, request("B3", false, new OpeningImport.Row(no, "300.00", "0", "0"))));
    assertThat(again.totalAmount()).isEqualTo("300.00");
  }

  @Test
  void dryRunWritesNothing() throws Exception {
    var no = studentWithEnrollment("004");
    var report =
        asActor(
            OPS,
            () ->
                migration.importOpenings(
                    SCHOOL,
                    request("B4", true, new OpeningImport.Row(no, "999.00", "5.00", "500.00"))));
    assertThat(report.mode()).isEqualTo("DRY_RUN");
    assertThat(report.ok()).isEqualTo(1);
    assertThat(report.totalAmount()).isEqualTo("999.00");
  }
}
