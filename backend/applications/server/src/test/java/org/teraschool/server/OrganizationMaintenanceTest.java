package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
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
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

/** 组织写入的权限、真实并发、幂等、回滚及 HTTP/第二入口一致性。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationMaintenanceTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      USER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();
  @LocalServerPort int port;
  @Autowired OrganizationCommands commands;
  @Autowired OrganizationQueries queries;
  @Autowired org.jooq.DSLContext sql;
  @Autowired TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,'维护测试机构')", TENANT);
    for (var id : List.of(SCHOOL, OTHER))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','维护测试学校')",
          id,
          TENANT,
          id);
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,'maintainer-fixture',?)",
        USER,
        TENANT,
        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    for (var id : List.of(SCHOOL, OTHER))
      update(
          "insert into identity.account_permission(account_id,tenant_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_READ','ALLOW')",
          USER,
          TENANT,
          id);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_MAINTAIN','ALLOW')",
        TENANT,
        USER,
        SCHOOL);
  }

  <T> T asActor(Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(USER.toString(), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  CreateOrganizationUnit command() {
    return new CreateOrganizationUnit(UUID.randomUUID(), SCHOOL, "CAMPUS", "合成校区");
  }

  long count(String table, UUID resource) throws Exception {
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement(
                "select count(*) from "
                    + table
                    + " where "
                    + (table.startsWith("audit") ? "resource_id" : "id")
                    + "=?")) {
      s.setObject(1, resource);
      var r = s.executeQuery();
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void createRetryAndRenamePreserveOriginalResult() throws Exception {
    var input = command();
    var result = asActor(() -> commands.createChild(input));
    assertThat(asActor(() -> commands.createChild(input))).isEqualTo(result);
    var renamed =
        asActor(() -> commands.rename(result.id(), new RenameOrganizationUnit(0L, "新校区名")));
    assertThat(renamed.version()).isEqualTo(1);
    assertThat(renamed.name()).isEqualTo("新校区名");
    assertThat(asActor(() -> commands.createChild(input))).isEqualTo(result);
    assertThatThrownBy(
            () ->
                asActor(
                    () ->
                        commands.createChild(
                            new CreateOrganizationUnit(
                                input.requestId(), SCHOOL, "CAMPUS", "变更参数"))))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(count("audit.business_event", result.id())).isEqualTo(2);
    update(
        "delete from identity.account_permission where action='ORGANIZATION_MAINTAIN' and account_id=? and effect='ALLOW'",
        USER);
    try {
      assertThatThrownBy(() -> asActor(() -> commands.createChild(input)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_MAINTAIN','ALLOW')",
          TENANT,
          USER,
          SCHOOL);
    }
  }

  @Test
  void denyOverridesAllowAndScopeIsActionSpecific() throws Exception {
    assertThat(asActor(() -> queries.schoolOptions("维护测试")))
        .extracting(OrganizationUnit::id)
        .containsExactly(SCHOOL);
    assertThatThrownBy(
            () ->
                asActor(
                    () ->
                        commands.createChild(
                            new CreateOrganizationUnit(
                                UUID.randomUUID(), OTHER, "DEPARTMENT", "部门"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_MAINTAIN','DENY')",
        TENANT,
        USER,
        SCHOOL);
    try {
      assertThat(asActor(() -> queries.schoolOptions(""))).isEmpty();
      assertThatThrownBy(() -> asActor(() -> commands.createChild(command())))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      update(
          "delete from identity.account_permission where action='ORGANIZATION_MAINTAIN' and account_id=? and effect='DENY'",
          USER);
    }
  }

  @Test
  void validationAndSchoolRenamingAreRejected() {
    assertThatThrownBy(
            () ->
                asActor(
                    () ->
                        commands.createChild(
                            new CreateOrganizationUnit(UUID.randomUUID(), SCHOOL, "SCHOOL", "学校"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    () ->
                        commands.createChild(
                            new CreateOrganizationUnit(
                                UUID.randomUUID(), SCHOOL, "CAMPUS", " \n "))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> asActor(() -> commands.rename(SCHOOL, new RenameOrganizationUnit(0L, "学校改名"))))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("UNIT_KIND_NOT_EDITABLE");
  }

  @Test
  void crossTenantInsertAndAuditTamperingRejected() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    UUID.randomUUID(),
                    () ->
                        sql.execute(
                            "insert into organization.unit(tenant_id,school_id,kind,name) values (?,?,'CAMPUS','越租户写入')",
                            TENANT,
                            SCHOOL)))
        .satisfies(this::assertPermissionDenied);
    assertThatThrownBy(
            () ->
                tx.execute(
                    TENANT,
                    () ->
                        sql.execute(
                            "update audit.business_event set new_name='篡改' where tenant_id=?",
                            TENANT)))
        .satisfies(this::assertPermissionDenied);
  }

  private void assertPermissionDenied(Throwable error) {
    while (error.getCause() != null) error = error.getCause();
    assertThat(error).isInstanceOf(java.sql.SQLException.class);
    assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("42501");
  }

  @Test
  void rollbackIncludesBusinessRequestAndAudit() throws Exception {
    var input = command();
    var id = new java.util.concurrent.atomic.AtomicReference<UUID>();
    assertThatThrownBy(
            () ->
                asActor(
                    () ->
                        tx.execute(
                            TENANT,
                            () -> {
                              id.set(commands.createChild(input).id());
                              throw new IllegalStateException("强制提交前失败");
                            })))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("organization.unit", id.get())).isZero();
    assertThat(count("audit.business_event", id.get())).isZero();
    assertThat(asActor(() -> commands.createChild(input)).id()).isNotEqualTo(id.get());
  }

  @Test
  void concurrentCreateOnlyCreatesOneFact() throws Exception {
    var input = command();
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<OrganizationUnit> job =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return asActor(() -> commands.createChild(input));
          };
      var a = pool.submit(job);
      var b = pool.submit(job);
      var result = a.get(20, TimeUnit.SECONDS);
      assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(result);
      assertThat(count("audit.business_event", result.id())).isEqualTo(1);
    }
  }

  @Test
  void concurrentRenameHasOneWinner() throws Exception {
    var unit = asActor(() -> commands.createChild(command()));
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<String> job =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              asActor(() -> commands.rename(unit.id(), new RenameOrganizationUnit(0L, "并发名称")));
              return "OK";
            } catch (OrganizationConflict e) {
              return e.code();
            }
          };
      var a = pool.submit(job);
      var b = pool.submit(job);
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
      assertThat(count("audit.business_event", unit.id())).isEqualTo(2);
    }
  }

  HttpResponse<String> get(HttpClient c, String path) throws Exception {
    return c.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient c, String path, String body, String token) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(
                "Content-Type",
                path.endsWith("login") ? "application/x-www-form-urlencoded" : "application/json");
    if (token != null) builder.header("X-CSRF-TOKEN", token);
    return c.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void actualHttpRequiresCsrfAndMatchesServiceRules() throws Exception {
    var c =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    String token = json.readTree(get(c, "/api/session/csrf").body()).get("token").asText();
    assertThat(
            post(c, "/api/session/login", "username=maintainer-fixture&password=" + PASSWORD, token)
                .statusCode())
        .isEqualTo(204);
    token = json.readTree(get(c, "/api/session/csrf").body()).get("token").asText();
    var body = json.writeValueAsString(command());
    assertThat(post(c, "/api/organization/units", body, null).statusCode()).isEqualTo(403);
    var response = post(c, "/api/organization/units", body, token);
    assertThat(response.statusCode()).isEqualTo(201);
    String id = json.readTree(response.body()).get("id").asText();
    assertThat(
            post(c, "/api/organization/units/" + id + "/rename", "{\"name\":\"漏版本\"}", token)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post(
                    c,
                    "/api/organization/units/" + id + "/rename",
                    "{\"expectedVersion\":0,\"name\":123}",
                    token)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post(
                    c,
                    "/api/organization/units/" + id + "/rename",
                    "{\"expectedVersion\":0,\"name\":\"改名\"}",
                    token)
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post(
                    c,
                    "/api/organization/units/" + id + "/rename",
                    "{\"expectedVersion\":0,\"name\":\"旧版本\"}",
                    token)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            post(
                    c,
                    "/api/organization/units",
                    body.substring(0, body.length() - 1) + ",\"tenantId\":\"" + TENANT + "\"}",
                    token)
                .statusCode())
        .isEqualTo(400);
  }
}
