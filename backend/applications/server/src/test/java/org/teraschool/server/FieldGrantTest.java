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

/** 字段级权限：功能动作之外按字段授权，DENY 优先，未授权字段脱敏并列出。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FieldGrantTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      READER = UUID.randomUUID(),
      PHONE_ONLY = UUID.randomUUID(),
      SENSITIVE = UUID.randomUUID();
  static final UUID STUDENT = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @Autowired StudentQueries students;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "字段机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','字段学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into archive.student(id,tenant_id,student_no,name,sex,birthday,cert_type,cert_code,"
            + "phone,email,school_id) values (?,?,?,?,?,?,?,?,?,?,?)",
        STUDENT,
        TENANT,
        "S1001",
        "字段学员",
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
    for (var entry :
        Map.of(READER, "field-reader", PHONE_ONLY, "field-phone", SENSITIVE, "field-sensitive")
            .entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var account : List.of(READER, PHONE_ONLY, SENSITIVE))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,'STUDENT_READ','ALLOW')",
          TENANT,
          account,
          SCHOOL);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'STUDENT_SENSITIVE_READ','ALLOW')",
        TENANT,
        SENSITIVE,
        SCHOOL);
    // 只授予手机号字段。
    update(
        "insert into identity.field_grant(tenant_id,account_id,school_id,field_key,effect)"
            + " values (?,?,?,'STUDENT_PHONE','ALLOW')",
        TENANT,
        PHONE_ONLY,
        SCHOOL);
    // 敏感读取账号下再显式拒绝证件号，验证 DENY 优先。
    update(
        "insert into identity.field_grant(tenant_id,account_id,school_id,field_key,effect)"
            + " values (?,?,?,'STUDENT_CERT','DENY')",
        TENANT,
        SENSITIVE,
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
  void plainReaderSeesNoSensitiveFields() {
    var profile = asActor(READER, () -> students.detail(STUDENT));
    assertThat(profile.phone()).isEqualTo("138****1111");
    assertThat(profile.certCode()).isEqualTo("****1234");
    assertThat(profile.maskedFields())
        .contains("STUDENT_PHONE", "STUDENT_CERT", "STUDENT_EMAIL", "STUDENT_BIRTHDAY");
  }

  @Test
  void phoneOnlyGrantRevealsJustPhone() {
    var profile = asActor(PHONE_ONLY, () -> students.detail(STUDENT));
    assertThat(profile.phone()).isEqualTo("13800001111");
    assertThat(profile.certCode()).isEqualTo("****1234");
    assertThat(profile.maskedFields()).contains("STUDENT_CERT").doesNotContain("STUDENT_PHONE");
  }

  @Test
  void explicitDenyWinsOverSensitiveAction() {
    var profile = asActor(SENSITIVE, () -> students.detail(STUDENT));
    assertThat(profile.phone()).isEqualTo("13800001111");
    // 证件号被显式拒绝：即使有 STUDENT_SENSITIVE_READ 也脱敏。
    assertThat(profile.certCode()).isEqualTo("****1234");
    assertThat(profile.maskedFields()).containsExactly("STUDENT_CERT");
  }
}
