package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.archive.api.*;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

/** 学员与家长档案：真实 PostgreSQL 下的范围、敏感字段、重复、幂等、版本、审计与两个入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudentProfileTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      CAMPUS = UUID.randomUUID(),
      MAINTAINER = UUID.randomUUID(),
      READER = UUID.randomUUID(),
      SENSITIVE = UUID.randomUUID();
  static final String MAINTAINER_PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired StudentCommands commands;
  @Autowired StudentQueries queries;
  @Autowired org.jooq.DSLContext sql;
  @Autowired TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update(
        "insert into identity.tenant(id,name) values (?,?),(?,?)",
        TENANT,
        "档案机构甲",
        OTHER_TENANT,
        "档案机构乙");
    for (var pair : new UUID[][] {{SCHOOL, SCHOOL}, {OTHER_SCHOOL, OTHER_SCHOOL}, {CAMPUS, SCHOOL}})
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,?,?)",
          pair[0],
          TENANT,
          pair[1],
          pair[0].equals(CAMPUS) ? "CAMPUS" : "SCHOOL",
          pair[0].equals(SCHOOL) ? "档案学校甲" : pair[0].equals(OTHER_SCHOOL) ? "档案学校乙" : "档案校区甲");
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values"
            + " (?,?,'archive-staff',?)",
        MAINTAINER,
        TENANT,
        encoder.encode(MAINTAINER_PASSWORD));
    for (var id : new UUID[] {READER, SENSITIVE})
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          id,
          TENANT,
          "archive-" + id,
          encoder.encode(MAINTAINER_PASSWORD));
    for (var action : new String[] {"STUDENT_READ", "STUDENT_MAINTAIN"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          MAINTAINER,
          SCHOOL,
          action);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'STUDENT_READ','ALLOW')",
        TENANT,
        READER,
        SCHOOL);
    for (var action : new String[] {"STUDENT_READ", "STUDENT_MAINTAIN", "STUDENT_SENSITIVE_READ"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          SENSITIVE,
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

  CreateStudent command(
      String name, String birthday, String certCode, StudentContactInput... contacts) {
    return new CreateStudent(
        UUID.randomUUID(),
        SCHOOL,
        CAMPUS,
        name,
        null,
        "FEMALE",
        birthday,
        certCode == null ? null : "ID_CARD",
        certCode,
        "13800005678",
        "student@example.test",
        List.of(contacts),
        false);
  }

  static StudentContactInput guardian(String name, String phone, boolean primary) {
    return new StudentContactInput(name, "MOTHER", phone, null, null, null, primary);
  }

  long count(String table, UUID resource) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement("select count(*) from " + table + " where resource_id=?")) {
      s.setObject(1, resource);
      var r = s.executeQuery();
      r.next();
      return r.getLong(1);
    }
  }

  long studentCount() {
    return sql.fetchOne("select count(*) from archive.student").get(0, Long.class);
  }

  @Test
  void createStoresProfileContactsAndAudit() throws Exception {
    var result =
        asActor(
            MAINTAINER,
            () ->
                commands.create(
                    command(
                        "档案学员甲",
                        "2015-03-04",
                        "110101201503041234",
                        guardian("档案家长甲", "13900001111", true))));
    assertThat(result.profile().studentNo()).matches("S\\d{6}");
    assertThat(result.profile().schoolName()).isEqualTo("档案学校甲");
    assertThat(result.profile().contacts()).hasSize(1);
    assertThat(result.profile().contacts().getFirst().primary()).isTrue();
    assertThat(result.profile().sensitiveFieldsMasked()).isTrue();
    assertThat(count("audit.student_event", result.profile().id())).isEqualTo(1);
  }

  @Test
  void retryKeepsFirstResultAndRejectsChangedInput() {
    var input = command("档案学员乙", "2016-05-06", "110101201605061234");
    var first = asActor(MAINTAINER, () -> commands.create(input));
    assertThat(asActor(MAINTAINER, () -> commands.create(input)).profile().id())
        .isEqualTo(first.profile().id());
    var changed =
        new CreateStudent(
            input.requestId(),
            SCHOOL,
            CAMPUS,
            "改了名字",
            null,
            "FEMALE",
            "2016-05-06",
            "ID_CARD",
            "110101201605061234",
            "13800005678",
            "student@example.test",
            List.of(),
            false);
    assertThatThrownBy(() -> asActor(MAINTAINER, () -> commands.create(changed)))
        .isInstanceOf(StudentConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void duplicateCertificateIsRejectedByDatabaseRule() {
    asActor(
        MAINTAINER, () -> commands.create(command("同证件一号", "2014-01-02", "110101201401021111")));
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () -> commands.create(command("同证件二号", "2014-01-02", "110101201401021111"))))
        .isInstanceOf(StudentConflict.class)
        .hasMessage("DUPLICATE_CERTIFICATE");
  }

  @Test
  void sameNameAndBirthdayOnlyHints() {
    var existing = asActor(MAINTAINER, () -> commands.create(command("档案学员丙", "2013-07-08", null)));
    var later = asActor(MAINTAINER, () -> commands.create(command("档案学员丙", "2013-07-08", null)));
    assertThat(later.profile().id()).isNotEqualTo(existing.profile().id());
    assertThat(later.possibleDuplicates())
        .extracting(DuplicateHint::studentId)
        .contains(existing.profile().id());
    assertThat(later.possibleDuplicates().getFirst().reason())
        .isEqualTo(DuplicateHint.SAME_NAME_AND_BIRTHDAY);
  }

  @Test
  void contactReusedOnlyWhenNameAndPhoneMatch() throws Exception {
    var shared = guardian("档案家长丁", "13700002222", true);
    asActor(MAINTAINER, () -> commands.create(command("档案学员丁一", "2012-02-03", null, shared)));
    var second =
        asActor(MAINTAINER, () -> commands.create(command("档案学员丁二", "2012-09-10", null, shared)));
    assertThat(second.reusedContactIds()).hasSize(1);
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement("select count(*) from archive.contact where phone='13700002222'")) {
      var r = s.executeQuery();
      r.next();
      assertThat(r.getLong(1)).isEqualTo(1);
    }
  }

  @Test
  void sensitiveFieldsMaskedWithoutPermission() throws Exception {
    var created =
        asActor(
            MAINTAINER,
            () -> commands.create(command("档案学员戊", "2011-11-12", "110101201111121234")));
    var masked = asActor(MAINTAINER, () -> queries.detail(created.profile().id()));
    assertThat(masked.sensitiveFieldsMasked()).isTrue();
    assertThat(masked.phone()).isEqualTo("138****5678");
    assertThat(masked.certCode()).isEqualTo("****1234");
    assertThat(masked.birthday()).isEqualTo("2011-11");
    var full = asActor(SENSITIVE, () -> queries.detail(created.profile().id()));
    assertThat(full.sensitiveFieldsMasked()).isFalse();
    assertThat(full.phone()).isEqualTo("13800005678");
    assertThat(full.certCode()).isEqualTo("110101201111121234");
    assertThat(full.birthday()).isEqualTo("2011-11-12");
    assertThat(count("audit.student_event", created.profile().id())).isEqualTo(2);
  }

  @Test
  void scopeFiltersReadsAndBlocksWrites() {
    asActor(MAINTAINER, () -> commands.create(command("档案学员己", "2010-04-05", null)));
    assertThat(asActor(READER, () -> queries.list(0, 100, "档案学员己")).items())
        .isNotEmpty()
        .allMatch(item -> item.schoolId().equals(SCHOOL));
    var unauthorized =
        new CreateStudent(
            UUID.randomUUID(),
            OTHER_SCHOOL,
            null,
            "越权学员",
            null,
            "MALE",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            false);
    assertThatThrownBy(() -> asActor(MAINTAINER, () -> commands.create(unauthorized)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor(READER, () -> commands.create(command("只读越权学员", "2010-01-01", null))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(asActor(MAINTAINER, () -> queries.capabilities()).maintainableSchools())
        .extracting(StudentCapabilities.SchoolOption::id)
        .containsExactly(SCHOOL);
  }

  @Test
  void staleUpdateConflictsAndValidUpdateBumpsVersion() throws Exception {
    var created =
        asActor(
            MAINTAINER,
            () -> commands.create(command("档案学员庚", "2009-09-09", "110101200909091234")));
    var stale =
        new UpdateStudent(
            99L,
            CAMPUS,
            "档案学员庚",
            null,
            "FEMALE",
            "2009-09-09",
            "ID_CARD",
            "110101200909091234",
            "13800005678",
            "student@example.test",
            "ACTIVE",
            List.of(guardian("档案家长庚", "13600003333", true)),
            false);
    assertThatThrownBy(
            () -> asActor(MAINTAINER, () -> commands.update(created.profile().id(), stale)))
        .isInstanceOf(StudentConflict.class)
        .hasMessage("VERSION_CONFLICT");
    var valid =
        new UpdateStudent(
            created.profile().version(),
            CAMPUS,
            "档案学员庚改名",
            null,
            "FEMALE",
            "2009-09-09",
            "ID_CARD",
            "110101200909091234",
            "13800005678",
            "student@example.test",
            "INACTIVE",
            List.of(guardian("档案家长庚", "13600003333", true)),
            false);
    var updated = asActor(MAINTAINER, () -> commands.update(created.profile().id(), valid));
    assertThat(updated.name()).isEqualTo("档案学员庚改名");
    assertThat(updated.status()).isEqualTo("INACTIVE");
    assertThat(updated.version()).isEqualTo(created.profile().version() + 1);
    assertThat(updated.contacts()).hasSize(1);
    assertThat(count("audit.student_event", created.profile().id())).isEqualTo(2);
  }

  @Test
  void statusChangeIsSeparateAndVersioned() throws Exception {
    var created =
        asActor(
            MAINTAINER,
            () -> commands.create(command("档案学员壬", "2007-07-07", "110101200707071234")));
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.changeStatus(
                            created.profile().id(), new ChangeStudentStatus(99L, "INACTIVE"))))
        .isInstanceOf(StudentConflict.class)
        .hasMessage("VERSION_CONFLICT");
    var changed =
        asActor(
            MAINTAINER,
            () ->
                commands.changeStatus(
                    created.profile().id(),
                    new ChangeStudentStatus(created.profile().version(), "INACTIVE")));
    assertThat(changed.status()).isEqualTo("INACTIVE");
    assertThat(changed.version()).isEqualTo(created.profile().version() + 1);
    assertThat(count("audit.student_event", created.profile().id())).isEqualTo(2);
    // 状态变更不提交敏感字段，因此返回仍按权限脱敏，数据库中的原文不受影响。
    var full = asActor(SENSITIVE, () -> queries.detail(created.profile().id()));
    assertThat(full.phone()).isEqualTo("13800005678");
    assertThat(full.status()).isEqualTo("INACTIVE");
    // 完整字段读取本身也留下独立审计。
    assertThat(count("audit.student_event", created.profile().id())).isEqualTo(3);
  }

  @Test
  void maskedEmailCannotBeWrittenBack() {
    var created = asActor(MAINTAINER, () -> commands.create(command("档案学员癸", "2006-06-06", null)));
    var masked =
        new UpdateStudent(
            created.profile().version(),
            CAMPUS,
            "档案学员癸",
            null,
            "FEMALE",
            null,
            null,
            null,
            "138****5678",
            "s***@example.test",
            "ACTIVE",
            List.of(),
            false);
    assertThatThrownBy(
            () -> asActor(MAINTAINER, () -> commands.update(created.profile().id(), masked)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inactiveSchoolBlocksWrite() throws Exception {
    update("update organization.unit set active=false where id=?", SCHOOL);
    try {
      assertThatThrownBy(
              () -> asActor(MAINTAINER, () -> commands.create(command("停用学校学员", null, null))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
          .hasMessageContaining("停用");
    } finally {
      update("update organization.unit set active=true where id=?", SCHOOL);
    }
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into archive.student(tenant_id,student_no,name,sex,school_id)"
                                + " values (?,?,?,'UNKNOWN',?)",
                            TENANT,
                            "S999997",
                            "越租户学员",
                            SCHOOL)))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient client, String path, String body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(
                "Content-Type",
                path.endsWith("login") ? "application/x-www-form-urlencoded" : "application/json");
    if (token != null) builder.header("X-CSRF-TOKEN", token);
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpMasksFieldsAndSharesServiceRules() throws Exception {
    HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    assertThat(get(client, "/api/archive/students").statusCode()).isEqualTo(401);
    var csrf = json.readTree(get(client, "/api/session/csrf").body());
    assertThat(
            post(
                    client,
                    "/api/session/login",
                    "username=archive-staff&password=" + MAINTAINER_PASSWORD,
                    csrf.get("token").asText())
                .statusCode())
        .isEqualTo(204);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    // 缺少 CSRF 头的写请求必须被拒绝，避免页面外入口绕过。
    assertThat(post(client, "/api/archive/students", "{}", null).statusCode()).isEqualTo(403);
    var input = command("档案学员辛", "2008-08-08", "110101200808081234");
    var body = json.writeValueAsString(input);
    var created = post(client, "/api/archive/students", body, token);
    assertThat(created.statusCode()).isEqualTo(201);
    var profile = json.readTree(created.body()).get("profile");
    var id = UUID.fromString(profile.get("id").asText());
    assertThat(profile.get("phone").asText()).isEqualTo("138****5678");
    assertThat(profile.get("certCode").asText()).isEqualTo("****1234");
    assertThat(profile.get("sensitiveFieldsMasked").asBoolean()).isTrue();
    var service = asActor(MAINTAINER, () -> queries.detail(id));
    assertThat(service.studentNo()).isEqualTo(profile.get("studentNo").asText());
    var listed = json.readTree(get(client, "/api/archive/students?size=100").body());
    assertThat(listed.get("total").asLong())
        .isEqualTo(asActor(MAINTAINER, () -> queries.list(0, 100, "").total()));
    // 同请求号重试返回首次结果；换参数则拒绝，HTTP 与直接入口使用同一套幂等规则。
    var retried = post(client, "/api/archive/students", body, token);
    assertThat(retried.statusCode()).isEqualTo(201);
    assertThat(json.readTree(retried.body()).get("profile").get("id").asText())
        .isEqualTo(id.toString());
    var changed =
        new CreateStudent(
            input.requestId(),
            SCHOOL,
            CAMPUS,
            "改过的名字",
            null,
            "FEMALE",
            "2008-08-08",
            "ID_CARD",
            "110101200808081234",
            "13800005678",
            "student@example.test",
            List.of(),
            false);
    var mismatch = post(client, "/api/archive/students", json.writeValueAsString(changed), token);
    assertThat(mismatch.statusCode()).isEqualTo(409);
    assertThat(json.readTree(mismatch.body()).get("code").asText())
        .isEqualTo("IDEMPOTENCY_MISMATCH");
  }
}
