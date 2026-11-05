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
import org.teraschool.archive.api.*;

/** 学员导出：字段按授权脱敏，导出写审计，跨学校范围受限。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudentExportTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      READER = UUID.randomUUID(),
      PHONE_ONLY = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @Autowired StudentQueries students;
  @Autowired StudentExportTaskService exportTasks;

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
        "insert into archive.student(id,tenant_id,student_no,name,sex,birthday,cert_type,cert_code,"
            + " phone,email,school_id) values (?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        TENANT,
        "E0001",
        "导出学员",
        "FEMALE",
        java.time.LocalDate.of(2015, 3, 4),
        "ID_CARD",
        "110101201503041234",
        "13800001111",
        "kid@example.com",
        SCHOOL);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry : Map.of(READER, "export-reader", PHONE_ONLY, "export-phone").entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var account : List.of(READER, PHONE_ONLY))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,'STUDENT_READ','ALLOW')",
          TENANT,
          account,
          SCHOOL);
    for (var account : List.of(READER, PHONE_ONLY))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,'STUDENT_EXPORT','ALLOW')",
          TENANT,
          account,
          SCHOOL);
    for (String action : List.of("ENROLLMENT_READ", "FUNDS_READ", "TEACHING_READ"))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          READER,
          SCHOOL,
          action);
    update(
        "insert into identity.field_grant(tenant_id,account_id,school_id,field_key,effect)"
            + " values (?,?,?,'STUDENT_PHONE','ALLOW')",
        TENANT,
        PHONE_ONLY,
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

  @Test
  void exportMasksFieldsWithoutGrant() {
    var csv = asActor(READER, () -> students.exportCsv(SCHOOL));
    var lines = csv.split("\\R");
    assertThat(lines[0]).startsWith("学员号,姓名,性别");
    assertThat(csv).contains("138****1111").contains("****1234");
    assertThat(csv).doesNotContain("13800001111").doesNotContain("110101201503041234");
  }

  @Test
  void exportRevealsGrantedFieldOnly() {
    var csv = asActor(PHONE_ONLY, () -> students.exportCsv(SCHOOL));
    assertThat(csv).contains("13800001111");
    assertThat(csv).doesNotContain("110101201503041234");
  }

  @Test
  void persistentExportTaskReusesSameRequestAndDownloadRechecksSensitiveFields() throws Exception {
    var request = new CreateStudentExport(SCHOOL, UUID.randomUUID());
    var created = asActor(READER, () -> exportTasks.create(request));
    var repeated = asActor(READER, () -> exportTasks.create(request));
    assertThat(repeated.id()).isEqualTo(created.id());
    assertThat(repeated.schoolId()).isEqualTo(SCHOOL);

    var artifact = asActor(PHONE_ONLY, () -> students.exportCsvArtifact(SCHOOL));
    assertThat(artifact.exposedFields()).contains("STUDENT_PHONE").doesNotContain("STUDENT_CERT");
    asActor(
        PHONE_ONLY,
        () -> {
          students.authorizeCsvDownload(SCHOOL, artifact.exposedFields());
          return null;
        });
    update(
        "delete from identity.field_grant where tenant_id=? and account_id=? and school_id=? and field_key='STUDENT_PHONE'",
        TENANT,
        PHONE_ONLY,
        SCHOOL);
    assertThatThrownBy(
            () ->
                asActor(
                    PHONE_ONLY,
                    () -> {
                      students.authorizeCsvDownload(SCHOOL, artifact.exposedFields());
                      return null;
                    }))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    update(
        "insert into identity.field_grant(tenant_id,account_id,school_id,field_key,effect)"
            + " values (?,?,?,'STUDENT_PHONE','ALLOW')",
        TENANT,
        PHONE_ONLY,
        SCHOOL);
  }

  @Test
  void persistentStudentExportRunsAndCanBeDownloadedByItsOwner() throws Exception {
    var created =
        asActor(
            READER, () -> exportTasks.create(new CreateStudentExport(SCHOOL, UUID.randomUUID())));
    var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    StudentExportTask status;
    do {
      exportTasks.dispatchPending();
      status = asActor(READER, () -> exportTasks.get(created.id()));
      if ("COMPLETED".equals(status.status())) break;
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    assertThat(status.status()).isEqualTo("COMPLETED");
    assertThat(status.rowCount()).isEqualTo(1);
    var content =
        asActor(
            READER,
            () ->
                new String(
                    exportTasks.download(created.id()), java.nio.charset.StandardCharsets.UTF_8));
    assertThat(content).contains("导出学员").contains("138****1111");
    assertThat(content).doesNotContain("13800001111");
  }

  @Test
  void exportOutsideSchoolScopeIsRejected() {
    assertThatThrownBy(() -> asActor(READER, () -> students.exportCsv(OTHER_SCHOOL)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void readPermissionAloneCannotStartAnExport() throws Exception {
    update(
        "delete from identity.account_permission where tenant_id=? and account_id=? and school_id=? and action='STUDENT_EXPORT'",
        TENANT,
        READER,
        SCHOOL);
    try {
      assertThatThrownBy(
              () ->
                  asActor(
                      READER,
                      () -> exportTasks.create(new CreateStudentExport(SCHOOL, UUID.randomUUID()))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(asActor(READER, () -> students.capabilities()).canExport()).isFalse();
    } finally {
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,'STUDENT_EXPORT','ALLOW')",
          TENANT,
          READER,
          SCHOOL);
    }
  }

  @Test
  void pendingExportCanBeCancelledByItsOwner() {
    var created =
        asActor(
            READER, () -> exportTasks.create(new CreateStudentExport(SCHOOL, UUID.randomUUID())));
    var cancelled = asActor(READER, () -> exportTasks.cancel(created.id()));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
  }

  @Test
  void persistentEnrollmentReceiptAndLessonExportsUseTheirOwnReadScope() throws Exception {
    var cases =
        Map.of(
            "ENROLLMENT", "学员号,学员姓名,产品,课程",
            "RECEIPTS", "学员号,学员姓名,金额,方式",
            "LESSONS", "班级,日期,开始,结束");
    for (var entry : cases.entrySet()) {
      var command = new CreateExport(entry.getKey(), SCHOOL, UUID.randomUUID(), "", "");
      var created = asActor(READER, () -> exportTasks.create(command));
      assertThat(created.exportType()).isEqualTo(entry.getKey());
      assertThat(asActor(READER, () -> exportTasks.list(entry.getKey(), SCHOOL)))
          .extracting(StudentExportTask::id)
          .contains(created.id());
      var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
      StudentExportTask status;
      do {
        exportTasks.dispatchPending();
        status = asActor(READER, () -> exportTasks.get(created.id()));
        if ("COMPLETED".equals(status.status())) break;
        Thread.sleep(50);
      } while (System.nanoTime() < deadline);
      assertThat(status.status()).isEqualTo("COMPLETED");
      var csv =
          asActor(
              READER,
              () ->
                  new String(
                      exportTasks.download(created.id()), java.nio.charset.StandardCharsets.UTF_8));
      assertThat(csv).startsWith(entry.getValue());
    }
  }

  @Test
  void exportRequestIdentityIncludesDateFiltersAndRejectsInvalidRanges() {
    var requestId = UUID.randomUUID();
    asActor(
        READER,
        () ->
            exportTasks.create(
                new CreateExport("RECEIPTS", SCHOOL, requestId, "2026-11-01", "2026-11-03")));
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        exportTasks.create(
                            new CreateExport(
                                "RECEIPTS", SCHOOL, requestId, "2026-11-02", "2026-11-03"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不同导出参数");
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        exportTasks.create(
                            new CreateExport(
                                "LESSONS", SCHOOL, UUID.randomUUID(), "2026-11-03", "2026-11-02"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("结束日期");
  }

  @Test
  void persistentEnrollmentDownloadRechecksCurrentReadScope() throws Exception {
    var created =
        asActor(
            READER,
            () ->
                exportTasks.create(
                    new CreateExport("ENROLLMENT", SCHOOL, UUID.randomUUID(), null, null)));
    var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    StudentExportTask status;
    do {
      exportTasks.dispatchPending();
      status = asActor(READER, () -> exportTasks.get(created.id()));
      if ("COMPLETED".equals(status.status())) break;
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    assertThat(status.status()).isEqualTo("COMPLETED");
    update(
        "delete from identity.account_permission where tenant_id=? and account_id=? and school_id=? and action='ENROLLMENT_READ'",
        TENANT,
        READER,
        SCHOOL);
    try {
      assertThatThrownBy(() -> asActor(READER, () -> exportTasks.download(created.id())))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,'ENROLLMENT_READ','ALLOW')",
          TENANT,
          READER,
          SCHOOL);
    }
  }
}
