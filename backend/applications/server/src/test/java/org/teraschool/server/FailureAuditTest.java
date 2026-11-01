package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 真实PG验证失败审计独立提交、身份归属、最小权限及HTTP前置拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FailureAuditTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      USER = UUID.randomUUID(),
      READER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();
  @Autowired OrganizationCommands commands;
  @Autowired TenantTransactions tx;
  @Autowired DSLContext sql;

  @Autowired
  @Qualifier("auditDataSource")
  DataSource audit;

  @LocalServerPort int port;
  final JsonMapper json = JsonMapper.builder().build();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,'审计机构'),(?,'隔离机构')", TENANT, OTHER);
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','审计学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    for (var id : List.of(USER, READER)) {
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          id,
          TENANT,
          id.toString(),
          PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
      update(
          "insert into identity.account_permission(account_id,tenant_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_READ','ALLOW')",
          id,
          TENANT,
          SCHOOL);
    }
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_MAINTAIN','ALLOW')",
        TENANT,
        USER,
        SCHOOL);
  }

  <T> T as(UUID user, Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var ctx = SecurityContextHolder.createEmptyContext();
    ctx.setAuthentication(
        new UsernamePasswordAuthenticationToken(EngineeringTest.principal(user), null, List.of()));
    SecurityContextHolder.setContext(ctx);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  CreateOrganizationUnit command() {
    return new CreateOrganizationUnit(
        UUID.randomUUID(), SCHOOL, "CAMPUS", "sensitive-name-not-in-failure");
  }

  String event(UUID trace) throws Exception {
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement(
                "select row_to_json(f)::text from audit.operation_failure f where trace_id=?")) {
      s.setObject(1, trace);
      var r = s.executeQuery();
      assertThat(r.next()).isTrue();
      String row = r.getString(1);
      assertThat(r.next()).isFalse();
      return row;
    }
  }

  int count(UUID trace) throws Exception {
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement("select count(*) from audit.operation_failure where trace_id=?")) {
      s.setObject(1, trace);
      var r = s.executeQuery();
      r.next();
      return r.getInt(1);
    }
  }

  @AfterEach
  void clean() {
    SecurityContextHolder.clearContext();
    assertThat(RequestTrace.id()).isNull();
  }

  @Test
  void rejectionSurvivesCallerRollbackAndDoesNotLeakInput() throws Exception {
    UUID trace;
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      trace = RequestTrace.id();
      assertThatThrownBy(
              () -> as(READER, () -> tx.execute(TENANT, () -> commands.createChild(command()))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    var row = json.readTree(event(trace));
    assertThat(row.get("tenant_id").asText()).isEqualTo(TENANT.toString());
    assertThat(row.get("actor_id").asText()).isEqualTo(READER.toString());
    assertThat(row.get("source").asText()).isEqualTo("APPLICATION");
    assertThat(row.get("code").asText()).isEqualTo("ACCESS_DENIED");
    assertThat(event(trace)).doesNotContain("sensitive-name-not-in-failure");
    assertThat(
            tx.execute(
                OTHER,
                () ->
                    sql.fetchCount(
                        org.jooq.impl.DSL.table("audit.operation_failure"),
                        org.jooq.impl.DSL.field("trace_id").eq(trace))))
        .isZero();
  }

  @Test
  void failedBusinessTransactionRollsBackButFailureRecordRemains() throws Exception {
    var input = command();
    UUID trace;
    update("revoke insert on audit.business_event from ts_app");
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      trace = RequestTrace.id();
      assertThatThrownBy(() -> as(USER, () -> commands.createChild(input)))
          .isInstanceOf(RuntimeException.class);
    } finally {
      update("grant insert on audit.business_event to ts_app");
    }
    assertThat(json.readTree(event(trace)).get("result").asText()).isEqualTo("FAILED");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from organization.creation_request where request_id=?",
                            input.requestId())
                        .get(0, Integer.class)))
        .isZero();
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from organization.unit where tenant_id=? and name=?",
                            TENANT,
                            input.name())
                        .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void revokedAccountStillHasTrustedAuditSubject() throws Exception {
    UUID trace;
    update("update identity.account set active=false where id=?", USER);
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      trace = RequestTrace.id();
      assertThatThrownBy(() -> as(USER, () -> commands.createChild(command())))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      update("update identity.account set active=true where id=?", USER);
    }
    assertThat(json.readTree(event(trace)).get("actor_id").asText()).isEqualTo(USER.toString());
  }

  @Test
  void unavailableAuditIsExplicitAndNotReportedAsStored() throws Exception {
    UUID trace;
    update("revoke insert on audit.operation_failure from ts_audit");
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      trace = RequestTrace.id();
      assertThatThrownBy(() -> as(READER, () -> commands.createChild(command())))
          .isInstanceOf(AuditUnavailable.class);
    } finally {
      update("grant insert on audit.operation_failure to ts_audit");
    }
    assertThat(count(trace)).isZero();
  }

  @Test
  void auditRoleCannotReadBusinessOrAlterFailureHistory() throws Exception {
    try (var c = audit.getConnection();
        var s = c.createStatement()) {
      var r =
          s.executeQuery(
              "select rolsuper,rolbypassrls,has_table_privilege(current_user,'audit.operation_failure','SELECT'),has_table_privilege(current_user,'audit.operation_failure','UPDATE') from pg_roles where rolname=current_user");
      r.next();
      for (int i = 1; i <= 4; i++) assertThat(r.getBoolean(i)).isFalse();
      assertThatThrownBy(() -> s.executeQuery("select * from identity.account"))
          .isInstanceOf(SQLException.class);
    }
    assertThatThrownBy(
            () ->
                tx.execute(
                    TENANT,
                    () ->
                        sql.execute(
                            "insert into audit.operation_failure(attempt_id,trace_id,action,source,result,code) values (?,?,'ORGANIZATION_CREATE','APPLICATION','FAILED','FORGED')",
                            UUID.randomUUID(),
                            UUID.randomUUID())))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void outerRollbackAuditFailurePreservesOriginalFailure() throws Exception {
    UUID trace;
    var input = command();
    update("revoke insert on audit.operation_failure from ts_audit");
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      trace = RequestTrace.id();
      assertThatThrownBy(
              () ->
                  as(
                      USER,
                      () ->
                          tx.execute(
                              TENANT,
                              () -> {
                                commands.createChild(input);
                                throw new IllegalStateException("original-outer-failure");
                              })))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("original-outer-failure");
    } finally {
      update("grant insert on audit.operation_failure to ts_audit");
    }
    assertThat(count(trace)).isZero();
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from organization.creation_request where request_id=?",
                            input.requestId())
                        .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void httpAuditUnavailableIs503WithTrace() throws Exception {
    var c = client();
    assertThat(
            post(c, "/api/session/login", "username=" + READER + "&password=" + PASSWORD, token(c))
                .statusCode())
        .isEqualTo(204);
    update("revoke insert on audit.operation_failure from ts_audit");
    try {
      var response =
          post(c, "/api/organization/units", json.writeValueAsString(command()), token(c));
      assertThat(response.statusCode()).isEqualTo(503);
      UUID trace = UUID.fromString(response.headers().firstValue("X-Trace-Id").orElseThrow());
      assertThat(json.readTree(response.body()).get("code").asText())
          .isEqualTo("AUDIT_UNAVAILABLE");
      assertThat(json.readTree(response.body()).get("traceId").asText())
          .isEqualTo(trace.toString());
      assertThat(count(trace)).isZero();
      var security = post(client(), "/api/organization/units", "{}", null);
      assertThat(security.statusCode()).isEqualTo(503);
      assertThat(json.readTree(security.body()).get("code").asText())
          .isEqualTo("AUDIT_UNAVAILABLE");
    } finally {
      update("grant insert on audit.operation_failure to ts_audit");
    }
  }

  @Test
  void successfulCommandFollowedByOuterRollbackHasFailureRecord() throws Exception {
    UUID trace;
    var input = command();
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      trace = RequestTrace.id();
      assertThatThrownBy(
              () ->
                  as(
                      USER,
                      () ->
                          tx.execute(
                              TENANT,
                              () -> {
                                commands.createChild(input);
                                throw new IllegalStateException("外层提交前失败");
                              })))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(json.readTree(event(trace)).get("code").asText())
        .isEqualTo("TRANSACTION_ROLLED_BACK");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from organization.creation_request where request_id=?",
                            input.requestId())
                        .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void malformedRouteDoesNotBreakSecurityRejection() throws Exception {
    var response = post(client(), "/api/organization/units/rename", "{}", null);
    assertThat(response.statusCode()).isEqualTo(403);
    UUID trace = UUID.fromString(response.headers().firstValue("X-Trace-Id").orElseThrow());
    assertThat(count(trace)).isZero();
  }

  @Test
  void httpMediaContractRejectionsKeepStatusAndAudit() throws Exception {
    var c = client();
    assertThat(
            post(c, "/api/session/login", "username=" + USER + "&password=" + PASSWORD, token(c))
                .statusCode())
        .isEqualTo(204);
    for (int status : List.of(415, 406)) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/organization/units"))
              .header("Content-Type", status == 415 ? "text/plain" : "application/json")
              .header("Accept", status == 406 ? "text/plain" : "application/json")
              .header("X-CSRF-TOKEN", token(c))
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command())))
              .build();
      var response = c.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(status);
      UUID trace = UUID.fromString(response.headers().firstValue("X-Trace-Id").orElseThrow());
      assertThat(json.readTree(event(trace)).get("code").asText())
          .isEqualTo(status == 415 ? "UNSUPPORTED_MEDIA_TYPE" : "NOT_ACCEPTABLE");
    }
  }

  HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  HttpResponse<String> get(HttpClient c, String path) throws Exception {
    return c.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient c, String path, String body, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(
                "Content-Type",
                path.endsWith("login") ? "application/x-www-form-urlencoded" : "application/json")
            .header("X-Trace-Id", "forged-client-trace");
    if (token != null) request.header("X-CSRF-TOKEN", token);
    return c.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String token(HttpClient c) throws Exception {
    return json.readTree(get(c, "/api/session/csrf").body()).get("token").asText();
  }

  @Test
  void httpCsrfMalformedBodyAndConflictHaveSingleLinkedRecord() throws Exception {
    var c = client();
    var rejected = post(c, "/api/organization/units", "{}", null);
    assertThat(rejected.statusCode()).isEqualTo(403);
    UUID trace = UUID.fromString(rejected.headers().firstValue("X-Trace-Id").orElseThrow());
    var row = json.readTree(event(trace));
    assertThat(row.get("tenant_id").isNull()).isTrue();
    assertThat(json.readTree(rejected.body()).get("traceId").asText()).isEqualTo(trace.toString());
    assertThat(
            post(c, "/api/session/login", "username=" + USER + "&password=" + PASSWORD, token(c))
                .statusCode())
        .isEqualTo(204);
    var malformed =
        post(
            c,
            "/api/organization/units",
            "{\"tenantId\":\"" + OTHER + "\",\"password\":\"not-for-audit\"}",
            token(c));
    assertThat(malformed.statusCode()).isEqualTo(400);
    trace = UUID.fromString(malformed.headers().firstValue("X-Trace-Id").orElseThrow());
    assertThat(json.readTree(event(trace)).get("tenant_id").asText()).isEqualTo(TENANT.toString());
    assertThat(event(trace)).doesNotContain("not-for-audit", OTHER.toString());
    var created = post(c, "/api/organization/units", json.writeValueAsString(command()), token(c));
    assertThat(created.statusCode()).isEqualTo(201);
    UUID successTrace = UUID.fromString(created.headers().firstValue("X-Trace-Id").orElseThrow());
    assertThat(count(successTrace)).isZero();
    try (var connection = EngineeringTest.admin();
        var statement =
            connection.prepareStatement(
                "select count(*) from audit.business_event where trace_id=?")) {
      statement.setObject(1, successTrace);
      var record = statement.executeQuery();
      record.next();
      assertThat(record.getInt(1)).isEqualTo(1);
    }
    String id = json.readTree(created.body()).get("id").asText();
    var conflict =
        post(
            c,
            "/api/organization/units/" + id + "/rename",
            "{\"expectedVersion\":4,\"name\":\"never-saved\"}",
            token(c));
    assertThat(conflict.statusCode()).isEqualTo(409);
    trace = UUID.fromString(conflict.headers().firstValue("X-Trace-Id").orElseThrow());
    assertThat(json.readTree(event(trace)).get("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(json.readTree(conflict.body()).get("traceId").asText()).isEqualTo(trace.toString());
  }
}
