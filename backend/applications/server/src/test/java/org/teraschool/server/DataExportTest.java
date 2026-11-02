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
import org.teraschool.teaching.api.*;

/** 经营数据导出：收款、报名、课次 CSV 都按学校范围，并写导出审计。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataExportTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @Autowired ProductCommands catalog;
  @Autowired EnrollmentCommands enrollments;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired FundsCommands funds;
  @Autowired FundsQueries fundsQueries;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired org.jooq.DSLContext sql;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "导出机构");
    for (var entry : Map.of(SCHOOL, "导出学校", OTHER_SCHOOL, "别的学校").entrySet())
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
          entry.getKey(),
          TENANT,
          entry.getKey(),
          entry.getValue());
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'导出课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'导出产品',1)",
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
        "export-ops",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action :
        new String[] {
          "PRODUCT_READ", "PRODUCT_MAINTAIN", "PRICE_PUBLISH", "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN", "ENROLLMENT_APPROVE", "FUNDS_READ", "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE", "TEACHING_READ", "TEACHING_MAINTAIN"
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

  UUID student(String no) throws Exception {
    var id = UUID.randomUUID();
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
        id,
        TENANT,
        no,
        "导出学员" + no,
        "FEMALE",
        SCHOOL);
    return id;
  }

  EnrollmentView enrollment(UUID student) throws Exception {
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
  void receiptsExportContainsRowsAndIsAudited() throws Exception {
    var student = student("X0001");
    asActor(
        OPS,
        () ->
            funds.createReceipt(
                new CreateReceipt(
                    UUID.randomUUID(), SCHOOL, student, "800", "CASH", "2026-02-10")));
    var csv =
        asActor(OPS, () -> fundsQueries.exportReceiptsCsv(SCHOOL, "2026-02-01", "2026-02-28"));
    assertThat(csv).startsWith("学员号,学员姓名,金额,方式,收款日期,状态");
    assertThat(csv).contains("X0001").contains("800.00");
    assertThat(exportAudit("RECEIPTS_EXPORTED")).isEqualTo(1);
  }

  @Test
  void enrollmentsExportContainsRowsAndIsAudited() throws Exception {
    var enrollment = enrollment(student("X0002"));
    var csv = asActor(OPS, () -> enrollmentQueries.exportCsv(SCHOOL));
    assertThat(csv).startsWith("学员号,学员姓名,产品,课程,价格,课时,净应收,状态,报名时间");
    assertThat(csv).contains("X0002").contains("1000.00");
    assertThat(exportAudit("ENROLLMENTS_EXPORTED")).isEqualTo(1);
    assertThat(enrollment.id()).isNotNull();
  }

  @Test
  void lessonsExportContainsRowsAndIsAudited() throws Exception {
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "导出班" + UUID.randomUUID())));
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
                    "导出课",
                    "1.00")));
    var csv =
        asActor(OPS, () -> teachingQueries.exportLessonsCsv(SCHOOL, "2026-03-01", "2026-03-31"));
    assertThat(csv).startsWith("班级,日期,开始,结束,教师,教室,课时,状态");
    assertThat(csv).contains("2026-03-02").contains("09:00");
    assertThat(exportAudit("LESSONS_EXPORTED")).isEqualTo(1);
  }

  @Test
  void exportsOutsideScopeAreRejected() {
    assertThatThrownBy(() -> asActor(OPS, () -> enrollmentQueries.exportCsv(OTHER_SCHOOL)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor(OPS, () -> fundsQueries.exportReceiptsCsv(OTHER_SCHOOL, "", "")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor(OPS, () -> teachingQueries.exportLessonsCsv(OTHER_SCHOOL, "", "")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private int exportAudit(String action) {
    try (var connection = EngineeringTest.admin();
        var statement =
            connection.prepareStatement(
                "select count(*) from ("
                    + "select action from audit.funds_event where action=?"
                    + " union all select action from audit.enrollment_event where action=?"
                    + " union all select action from audit.teaching_event where action=?"
                    + ") t")) {
      statement.setString(1, action);
      statement.setString(2, action);
      statement.setString(3, action);
      var rows = statement.executeQuery();
      rows.next();
      return rows.getInt(1);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
