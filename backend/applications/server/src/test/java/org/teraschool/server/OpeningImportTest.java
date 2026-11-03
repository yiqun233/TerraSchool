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
  @Autowired org.teraschool.funds.api.FundsCommands funds;
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
    assertThatThrownBy(() -> asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "B4")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reconciliationMatchesAfterImport() throws Exception {
    var no = studentWithEnrollment("005");
    asActor(
        OPS,
        () ->
            migration.importOpenings(
                SCHOOL,
                request("B5", false, new OpeningImport.Row(no, "700.00", "3.00", "300.00"))));
    var report = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "B5"));
    assertThat(report.completeness()).isEqualTo("COMPLETE");
    assertThat(report.rows()).isEqualTo(1);
    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.mismatched()).isZero();
    assertThat(report.expectedAmount()).isEqualTo("700.00");
    assertThat(report.actualAmount()).isEqualTo("700.00");
    assertThat(report.expectedHours()).isEqualTo("3.00");
  }

  @Test
  void reconciliationDetectsExtraOpening() throws Exception {
    var no = studentWithEnrollment("006");
    asActor(
        OPS,
        () ->
            migration.importOpenings(
                SCHOOL, request("B6", false, new OpeningImport.Row(no, "200.00", "0", "0"))));
    // 额外再记一笔期初余额（模拟人工重复导入）：对账必须报出 150.00 的差额。
    var studentId = studentIdOf(no);
    asActor(
        OPS,
        () ->
            funds.openBalance(
                new OpenBalance(
                    UUID.randomUUID(), SCHOOL, studentId, "150.00", "2026-02-10", "重复导入")));
    var report = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "B6"));
    assertThat(report.matched()).isZero();
    assertThat(report.mismatched()).isEqualTo(1);
    assertThat(report.expectedAmount()).isEqualTo("200.00");
    assertThat(report.actualAmount()).isEqualTo("200.00");
    assertThat(report.details())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.amountDiff()).isEqualTo("0.00");
              assertThat(row.status()).isEqualTo("UNATTRIBUTED");
            });
  }

  @Test
  void failedHoursRollBackBalanceAndDoNotPoisonOtherRows() throws Exception {
    var invalid = studentWithEnrollment("007");
    var valid = studentWithEnrollment("008");
    var result =
        asActor(
            OPS,
            () ->
                migration.importOpenings(
                    SCHOOL,
                    request(
                        "atomic",
                        false,
                        new OpeningImport.Row(invalid, "100.00", "11.00", "20.00"),
                        new OpeningImport.Row(valid, "50.00", "1.00", "30.00"))));
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.ok()).isEqualTo(1);
    assertThat(result.totalAmount()).isEqualTo("50.00");
    var invalidStudent = studentIdOf(invalid);
    assertThat(asActor(OPS, () -> fundsQueries.balance(invalidStudent)).total()).isEqualTo("0.00");
  }

  @Test
  void replayCannotChangeConsumptionAndIndependentBatchesDoNotPolluteReconciliation()
      throws Exception {
    var no = studentWithEnrollment("009");
    var first = request("batch-a", false, new OpeningImport.Row(no, "100.00", "1.00", "80.00"));
    assertThat(asActor(OPS, () -> migration.importOpenings(SCHOOL, first)).ok()).isEqualTo(1);
    assertThat(asActor(OPS, () -> migration.importOpenings(SCHOOL, first)).skipped()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        migration.importOpenings(
                            SCHOOL,
                            request(
                                "batch-a",
                                false,
                                new OpeningImport.Row(no, "100.00", "1.00", "90.00")))))
        .isInstanceOf(org.teraschool.migration.api.MigrationConflict.class)
        .hasMessage("OPENING_BATCH_MISMATCH");
    assertThat(
            asActor(
                    OPS,
                    () ->
                        migration.importOpenings(
                            SCHOOL,
                            request(
                                "batch-b", false, new OpeningImport.Row(no, "50.00", "0", "0"))))
                .ok())
        .isEqualTo(1);
    var report = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "batch-a"));
    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.actualAmount()).isEqualTo("100.00");
    assertThat(report.details().get(0).actualConsumedAmount()).isEqualTo("80.00");
  }

  @Test
  void openingDateMustBeExplicit() {
    var input =
        new OpeningImport(
            "missing-date", "", "旧库迁移", List.of(new OpeningImport.Row("X", "1", "0", "0")), false);
    assertThatThrownBy(() -> asActor(OPS, () -> migration.importOpenings(SCHOOL, input)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("期初日期");
  }

  @Test
  void failedRowsPersistAndIdenticalRetryCompletesManifest() throws Exception {
    var existing = studentWithEnrollment("011");
    var absent = "I-MISSING-011";
    var input =
        request(
            "manifest-retry",
            false,
            new OpeningImport.Row(existing, "25.00", "0", "0"),
            new OpeningImport.Row(absent, "40.00", "0", "0"));
    var first = asActor(OPS, () -> migration.importOpenings(SCHOOL, input));
    assertThat(first.ok()).isEqualTo(1);
    assertThat(first.failed()).isEqualTo(1);
    var incomplete = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "manifest-retry"));
    assertThat(incomplete.completeness()).isEqualTo("INCOMPLETE");
    assertThat(incomplete.rows()).isEqualTo(2);
    assertThat(incomplete.expectedAmount()).isNull();
    assertThat(incomplete.details())
        .anySatisfy(
            row -> {
              assertThat(row.rowNumber()).isEqualTo(2);
              assertThat(row.status()).isEqualTo("FAILED");
              assertThat(row.detail()).contains("没有该学员");
            });
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,'补齐学员','FEMALE',?)",
        UUID.randomUUID(),
        TENANT,
        absent,
        SCHOOL);
    var retry = asActor(OPS, () -> migration.importOpenings(SCHOOL, input));
    assertThat(retry.skipped()).isEqualTo(1);
    assertThat(retry.ok()).isEqualTo(1);
    var complete = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "manifest-retry"));
    assertThat(complete.completeness()).isEqualTo("COMPLETE");
    assertThat(complete.rows()).isEqualTo(2);
    assertThat(complete.matched()).isEqualTo(2);
    assertThat(complete.expectedAmount()).isEqualTo("65.00");
  }

  @Test
  void interruptedPendingManifestIsVisibleAndCanResume() throws Exception {
    var no = studentWithEnrollment("013");
    update(
        "insert into migration.opening_batch(tenant_id,school_id,batch_id,occurred_on,reason,row_count) values (?,?,'manifest-pending','2026-02-10','旧库迁移',1)",
        TENANT,
        SCHOOL);
    update(
        "insert into migration.opening_batch_row(tenant_id,school_id,batch_id,row_no,row_is_null,student_no,amount,hours,consumed_amount,status) values (?,?,'manifest-pending',1,false,?,'30.00','0','0','PENDING')",
        TENANT,
        SCHOOL,
        no);
    var before = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "manifest-pending"));
    assertThat(before.completeness()).isEqualTo("INCOMPLETE");
    assertThat(before.details())
        .singleElement()
        .satisfies(row -> assertThat(row.status()).isEqualTo("PENDING"));
    var input = request("manifest-pending", false, new OpeningImport.Row(no, "30.00", "0", "0"));
    assertThat(asActor(OPS, () -> migration.importOpenings(SCHOOL, input)).ok()).isEqualTo(1);
    assertThat(
            asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "manifest-pending"))
                .completeness())
        .isEqualTo("COMPLETE");
  }

  @Test
  void duplicateSourceRowsRemainVisibleAndCannotBeRemovedFromRetry() throws Exception {
    var no = studentWithEnrollment("012");
    var row = new OpeningImport.Row(no, "20.00", "0", "0");
    var input = request("manifest-duplicate", false, row, row);
    var report = asActor(OPS, () -> migration.importOpenings(SCHOOL, input));
    assertThat(report.failed()).isEqualTo(1);
    var reconcile = asActor(OPS, () -> migration.reconcileOpenings(SCHOOL, "manifest-duplicate"));
    assertThat(reconcile.completeness()).isEqualTo("INCOMPLETE");
    assertThat(reconcile.details())
        .anySatisfy(
            detail -> {
              assertThat(detail.rowNumber()).isEqualTo(2);
              assertThat(detail.status()).isEqualTo("FAILED");
            });
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        migration.importOpenings(
                            SCHOOL, request("manifest-duplicate", false, row))))
        .isInstanceOf(org.teraschool.migration.api.MigrationConflict.class)
        .hasMessage("OPENING_BATCH_MISMATCH");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        migration.importOpenings(SCHOOL, request("manifest-duplicate", true, row))))
        .isInstanceOf(org.teraschool.migration.api.MigrationConflict.class)
        .hasMessage("OPENING_BATCH_MISMATCH");
  }

  @Test
  void anotherAuthorizedOperatorCannotDuplicateSameBatchRow() throws Exception {
    var no = studentWithEnrollment("010");
    var other = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        other,
        TENANT,
        "opening-other",
        "{noop}unused");
    for (var action : new String[] {"MIGRATION_MANAGE", "FUNDS_READ", "TEACHING_MAINTAIN"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          TENANT,
          other,
          SCHOOL,
          action);
    var input = request("same-row", false, new OpeningImport.Row(no, "200.00", "1.00", "50.00"));
    assertThat(asActor(OPS, () -> migration.importOpenings(SCHOOL, input)).ok()).isEqualTo(1);
    assertThat(asActor(other, () -> migration.importOpenings(SCHOOL, input)).skipped())
        .isEqualTo(1);
    var student = studentIdOf(no);
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).total()).isEqualTo("200.00");
  }

  private UUID studentIdOf(String studentNo) throws Exception {
    try (var connection = EngineeringTest.admin();
        var statement =
            connection.prepareStatement("select id from archive.student where student_no=?")) {
      statement.setString(1, studentNo);
      var rows = statement.executeQuery();
      rows.next();
      return rows.getObject(1, UUID.class);
    }
  }
}
