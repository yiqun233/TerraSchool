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
  void exportOutsideSchoolScopeIsRejected() {
    assertThatThrownBy(() -> asActor(READER, () -> students.exportCsv(OTHER_SCHOOL)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
}
