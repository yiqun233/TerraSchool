package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.organization.api.OrganizationQueries;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

/** 使用迁移后的真实临时 PostgreSQL；角色与合成数据不进入产品初始化。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineeringTest {
  static final UUID A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      S = UUID.randomUUID(),
      S2 = UUID.randomUUID(),
      SB = UUID.randomUUID(),
      USER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();
  @LocalServerPort int port;
  @Autowired OrganizationQueries queries;
  @Autowired TenantTransactions tx;
  @Autowired DSLContext sql;
  @Autowired BrandingService branding;
  @Autowired org.teraschool.identity.api.CurrentActor actors;

  @Autowired
  @Qualifier("schedulerDataSource")
  DataSource schedulerDataSource;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("teraschool.database.url", () -> System.getenv("TS_JDBC_URL"));
    for (String kind : List.of("app", "identity", "session", "audit", "scheduler", "platform")) {
      r.add("teraschool.database." + kind + ".username", () -> "ts_" + kind);
      r.add(
          "teraschool.database." + kind + ".password",
          () -> System.getenv("TS_" + kind.toUpperCase() + "_PASSWORD"));
    }
    r.add("server.servlet.session.cookie.secure", () -> false);
    // 每个测试类有独立上下文与连接池；连接池总量会随类数增长，
    // 限制单池大小并允许空闲回收，避免临时库连接数被打满（表现为 Failed to obtain JDBC Connection）。
    // 每个测试类有独立上下文与连接池：每池 2 条（并发用例需要第二条连接等锁），
    // 配合临时库放宽的 max_connections，避免缓存上下文把连接数打满。
    r.add("teraschool.database.pool-size", () -> "2");
  }

  @Test
  void durableSchedulerUsesDedicatedRoleWithoutBusinessSchemaAccess() throws Exception {
    var scheduler = new JdbcTemplate(schedulerDataSource);
    assertThat(scheduler.queryForObject("select current_user", String.class))
        .isEqualTo("ts_scheduler");
    assertThat(
            scheduler.queryForObject(
                "select rolbypassrls from pg_roles where rolname=current_user", Boolean.class))
        .isFalse();
    assertThat(scheduler.queryForObject("select count(*) from infra.scheduled_tasks", Long.class))
        .isNotNull();
    assertThatThrownBy(
            () -> scheduler.queryForObject("select count(*) from archive.student", Long.class))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  static Connection admin() throws Exception {
    return DriverManager.getConnection(
        System.getenv("TS_JDBC_URL"), "postgres", System.getenv("TS_ADMIN_PASSWORD"));
  }

  static void update(String text, Object... args) throws Exception {
    try (var c = admin();
        var s = c.prepareStatement(text)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      s.executeUpdate();
    }
  }

  /** 合成业务用例明确配置免批阈值，避免依赖生产环境缺省策略。 */
  static void seedExplicitApprovalPolicy(UUID tenant, UUID school) throws Exception {
    for (var action :
        List.of(
            "REFUND_HOURS",
            "CASH_REFUND",
            "ENROLLMENT_DISCOUNT",
            "SERVICE_FEE_REFUND",
            "SERVICE_FEE_REVERSAL"))
      update(
          "insert into approval.policy(tenant_id,school_id,action,free_below,active) values (?,?,?,999999999999.99,true)",
          tenant,
          school,
          action);
  }

  static String principal(UUID account) {
    try (var c = admin();
        var s = c.prepareStatement("select credential_version from identity.account where id=?")) {
      s.setObject(1, account);
      var r = s.executeQuery();
      if (!r.next()) return account.toString();
      return account + "~" + r.getLong(1);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?), (?,?)", A, "合成机构甲", B, "合成机构乙");
    for (var pair : List.of(new UUID[] {A, S}, new UUID[] {A, S2}, new UUID[] {B, SB}))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','合成学校')",
          pair[1],
          pair[0],
          pair[1]);
    update(
        "insert into organization.unit(tenant_id,school_id,kind,name) values (?,?,'CAMPUS','合成校区'),(?,?,'DEPARTMENT','合成部门')",
        A,
        S,
        A,
        S);
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?, ?)",
        USER,
        A,
        "fixture-a",
        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    update(
        "insert into identity.account_permission(account_id,tenant_id,school_id,action,effect) values (?,?,?,'ORGANIZATION_READ','ALLOW')",
        USER,
        A,
        S);
  }

  @BeforeEach
  void actor() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(principal(USER), null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void schoolScopeBeforeCountAndPagination() {
    var result = queries.list(0, 2);
    assertThat(result.total()).isEqualTo(3);
    assertThat(result.items()).hasSize(2).allMatch(u -> u.schoolId().equals(S));
    assertThat(queries.list(1, 2).items()).hasSize(1);
    assertThat(queries.list(0, 100).items())
        .extracting(u -> u.kind())
        .containsExactlyInAnyOrder("SCHOOL", "CAMPUS", "DEPARTMENT");
  }

  @Test
  void revokedPermissionAppliesToNextServiceCall() throws Exception {
    update(
        "insert into identity.account_permission(account_id,tenant_id,action,effect) select id,tenant_id,'ORGANIZATION_READ','DENY' from identity.account where id=?",
        USER);
    try {
      assertThatThrownBy(() -> queries.list(0, 20))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      update(
          "delete from identity.account_permission where action='ORGANIZATION_READ' and school_id is null and effect='DENY' and account_id=?",
          USER);
    }
  }

  @Test
  void tenantDisabledAppliesToExistingActor() throws Exception {
    update("update identity.tenant set active=false where id=?", A);
    try {
      assertThatThrownBy(() -> queries.list(0, 20))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      update("update identity.tenant set active=true where id=?", A);
    }
  }

  @Test
  void missingIdentityAndInvalidPageDenied() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> queries.list(0, 20))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    actor();
    assertThatThrownBy(() -> queries.list(0, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void brandingDefaultsDerivesPersistsAndIsTenantIsolated() throws Exception {
    update(
        "insert into identity.account_permission(account_id,tenant_id,action,effect)"
            + " values (?,?,'TENANT_BRANDING_WRITE','ALLOW') on conflict do nothing",
        USER,
        A);
    actor();
    var initial = branding.tenant(A, true);
    assertThat(initial.version()).isZero();
    assertThat(initial.seed()).isEqualTo("#254c78");
    assertThat(initial.presets()).hasSize(6);

    var updated = branding.updateTenant(A, USER, new BrandingService.Update("启明教育", "#3b4e9b", 0));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.displayName()).isEqualTo("启明教育");
    assertThat(updated.brand()).isEqualTo("#3b4e9b");
    assertThat(updated.canWrite()).isTrue();

    assertThatThrownBy(
            () -> branding.updateTenant(A, USER, new BrandingService.Update("改坏", "#254c78", 0)))
        .isInstanceOf(BrandingService.VersionConflict.class);

    // 另一个租户读不到 A 的记录，只能看到自己的默认值。
    var isolated = branding.tenant(B, false);
    assertThat(isolated.version()).isZero();
    assertThat(isolated.displayName()).isNull();

    // 平台只读同一份呈现记录，可看到租户名与品牌值。
    var platformView = branding.platformTenant(A, false);
    assertThat(platformView.brand()).isEqualTo("#3b4e9b");
    assertThat(platformView.displayName()).isEqualTo("启明教育");
    assertThat(platformView.canWrite()).isFalse();

    var operator = UUID.randomUUID();
    var platformUpdate =
        branding.updatePlatform(A, operator, new BrandingService.Update("平台代改", "#0f6e6e", 1));
    assertThat(platformUpdate.version()).isEqualTo(2);
    assertThat(platformUpdate.brand()).isEqualTo("#0f6e6e");
    // 回滚复制历史为新版本，版本号继续递增。
    var rolledBack = branding.rollbackPlatform(A, operator, 1);
    assertThat(rolledBack.version()).isEqualTo(3);
    assertThat(rolledBack.brand()).isEqualTo("#3b4e9b");

    var history = branding.historyPlatform(A);
    assertThat(history).hasSize(3);
    assertThat(history.getFirst().version()).isEqualTo(3);
    assertThat(history.getFirst().brand()).isEqualTo("#3b4e9b");
    assertThat(history.getLast().version()).isEqualTo(1);
  }

  @Test
  void brandingLogoValidatesBeforeStorageAndRejectsNonImage() throws Exception {
    update(
        "insert into identity.account_permission(account_id,tenant_id,action,effect)"
            + " values (?,?,'TENANT_BRANDING_WRITE','ALLOW') on conflict do nothing",
        USER,
        A);
    actor();
    assertThatThrownBy(() -> branding.uploadLogo(A, USER, "WIDE", new byte[] {1, 2, 3}, null))
        .isInstanceOf(BrandingService.LogoInvalid.class);

    var image =
        new java.awt.image.BufferedImage(120, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var output = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", output);
    assertThatThrownBy(
            () -> branding.uploadLogo(A, USER, "WIDE", output.toByteArray(), "image/png"))
        .isInstanceOf(BrandingService.LogoUnavailable.class);
  }

  @Test
  void brandingInvalidIfMatchIsClientError() throws Exception {
    update(
        "insert into identity.account_permission(account_id,tenant_id,action,effect)"
            + " values (?,?,'TENANT_BRANDING_WRITE','ALLOW') on conflict do nothing",
        USER,
        A);
    actor();
    var http = new BrandingHttp(branding, actors);
    var response = new org.springframework.mock.web.MockHttpServletResponse();

    // 非法 If-Match 是客户端输入错误：必须落在品牌错误映射里，返回 400。
    var invalid = http.put(new BrandingHttp.Update("坏头", "#3b4e9b", null), "abc", response);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(
            ((org.springframework.http.ProblemDetail) invalid.getBody())
                .getProperties()
                .get("code"))
        .isEqualTo("INVALID_BRANDING");

    // 合法但过期的版本号仍然走版本冲突，不能退化成 500。
    var stale = http.put(new BrandingHttp.Update("过期", "#3b4e9b", null), "999", response);
    assertThat(stale.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void brandingRejectsHeaderDimensionsBeforeDecoding() throws Exception {
    update(
        "insert into identity.account_permission(account_id,tenant_id,action,effect)"
            + " values (?,?,'TENANT_BRANDING_WRITE','ALLOW') on conflict do nothing",
        USER,
        A);
    actor();
    // 24 字节的合法 PNG 头，声明 20000×20000：体积远低于 200KB，解码后是 4 亿像素。
    var header = new byte[24];
    header[0] = (byte) 0x89;
    header[1] = 'P';
    header[2] = 'N';
    header[3] = 'G';
    header[4] = 0x0d;
    header[5] = 0x0a;
    header[6] = 0x1a;
    header[7] = 0x0a;
    header[11] = 13;
    header[12] = 'I';
    header[13] = 'H';
    header[14] = 'D';
    header[15] = 'R';
    for (int offset : List.of(16, 20)) {
      header[offset] = 0;
      header[offset + 1] = 0;
      header[offset + 2] = 0x4e;
      header[offset + 3] = 0x20;
    }
    assertThatThrownBy(() -> branding.uploadLogo(A, USER, "WIDE", header, "image/png"))
        .isInstanceOf(BrandingService.LogoInvalid.class)
        .hasMessageContaining("最大边长");
  }

  @Test
  void brandingFirstWriteIsSerialized() throws Exception {
    var tenant = UUID.randomUUID();
    var account = UUID.randomUUID();
    update("insert into identity.tenant(id,name) values (?,?)", tenant, "并发机构");
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        account,
        tenant,
        "fixture-concurrent",
        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    update(
        "insert into identity.account_permission(account_id,tenant_id,action,effect)"
            + " values (?,?,'TENANT_BRANDING_WRITE','ALLOW')",
        account,
        tenant);

    // 租户还没有品牌行时，FOR UPDATE 锁不到行；两个请求会各自算出版本 1。
    // 租户级咨询锁让它们串行，第二个应当收到版本冲突而不是数据库主键异常。
    var ready = new java.util.concurrent.CountDownLatch(2);
    var go = new java.util.concurrent.CountDownLatch(1);
    var outcomes = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    Runnable attempt =
        () -> {
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new UsernamePasswordAuthenticationToken(principal(account), null, List.of()));
          ready.countDown();
          try {
            go.await();
            branding.updateTenant(
                tenant, account, new BrandingService.Update("并发机构", "#3b4e9b", 0));
            outcomes.add("ok");
          } catch (BrandingService.VersionConflict conflict) {
            outcomes.add("conflict");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcomes.add("interrupted");
          } catch (RuntimeException other) {
            outcomes.add(other.getClass().getSimpleName());
          } finally {
            SecurityContextHolder.clearContext();
          }
        };
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      go.countDown();
      first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      second.get(30, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(outcomes).containsExactlyInAnyOrder("ok", "conflict");
    assertThat(branding.platformTenant(tenant, false).version()).isEqualTo(1);
  }

  @Test
  void ordinaryRoleAndRls() {
    var role =
        sql.fetchOne(
            "select rolsuper,rolbypassrls,has_table_privilege(current_user,'organization.unit','TRUNCATE') as trunc from pg_roles where rolname=current_user");
    assertThat(role.get(0, Boolean.class)).isFalse();
    assertThat(role.get(1, Boolean.class)).isFalse();
    assertThat(role.get(2, Boolean.class)).isFalse();
    assertThat(sql.fetchCount(org.jooq.impl.DSL.table("organization.unit"))).isZero();
    assertThat(tx.execute(B, () -> sql.fetchCount(org.jooq.impl.DSL.table("organization.unit"))))
        .isEqualTo(1);
    assertThat(tx.execute(A, () -> sql.fetchCount(org.jooq.impl.DSL.table("organization.unit"))))
        .isEqualTo(4);
  }

  @Test
  void nestedTenantAndRollbackCannotLeak() {
    assertThatThrownBy(() -> tx.execute(A, () -> tx.execute(B, () -> true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sql.fetchCount(org.jooq.impl.DSL.table("organization.unit"))).isZero();
    assertThatThrownBy(() -> sql.fetch("select * from identity.account"))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }

  @Test
  void crossTenantAndWrongParentRejected() throws Exception {
    assertThatThrownBy(
            () ->
                update(
                    "insert into organization.unit(tenant_id,school_id,kind,name) values (?,?,'CAMPUS','错误关联')",
                    A,
                    SB))
        .isInstanceOf(SQLException.class);
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  HttpResponse<String> post(HttpClient client, String path, String data) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(data))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpSessionCsrfContractAndSecondEntry() throws Exception {
    var c = client();
    assertThat(get(c, "/api/organization/units").statusCode()).isEqualTo(401);
    assertThat(
            post(c, "/api/session/login", "username=fixture-a&password=" + PASSWORD).statusCode())
        .isEqualTo(403);
    var mapper = JsonMapper.builder().build();
    var csrf = mapper.readTree(get(c, "/api/session/csrf").body());
    String body =
        "username=fixture-a&password="
            + PASSWORD
            + "&"
            + csrf.get("parameterName").asText()
            + "="
            + URLEncoder.encode(
                csrf.get("token").asText(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(post(c, "/api/session/login", body).statusCode()).isEqualTo(204);
    var response = get(c, "/api/organization/units");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).get("total").asInt())
        .isEqualTo(queries.list(0, 20).total());
    assertThat(get(c, "/api/organization/units?size=101").statusCode()).isEqualTo(400);
    var schema = get(c, "/v3/api-docs");
    assertThat(schema.statusCode()).isEqualTo(200);
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/openapi.json"), schema.body());
    update("update identity.account set active=false where id=?", USER);
    try {
      assertThat(get(c, "/api/organization/units").statusCode()).isEqualTo(401);
    } finally {
      update("update identity.account set active=true where id=?", USER);
    }
    csrf = mapper.readTree(get(c, "/api/session/csrf").body());
    assertThat(
            post(
                    c,
                    "/api/session/logout",
                    csrf.get("parameterName").asText()
                        + "="
                        + URLEncoder.encode(
                            csrf.get("token").asText(), java.nio.charset.StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(204);
    assertThat(get(c, "/api/organization/units").statusCode()).isEqualTo(401);
  }
}
