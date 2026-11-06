package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 平台身份的最小闭环与隔离边界。
 *
 * <p>这组用例要证明的不是“能登录”，而是**两套身份互不通用**：租户会话调不到平台接口， 平台会话调不到业务接口，且平台数据库角色读不到业务表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIdentityTest {
  static final UUID OPERATOR_ID = UUID.randomUUID();
  static final String OPERATOR_LOGIN = "ops-" + OPERATOR_ID.toString().substring(0, 8);
  static final String OPERATOR_PASSWORD = "Platform-Pass-" + UUID.randomUUID();
  static final UUID TENANT = UUID.randomUUID();
  static final String TENANT_LOGIN = "tenant-" + TENANT.toString().substring(0, 8);
  static final String TENANT_PASSWORD = "Tenant-Pass-" + UUID.randomUUID();

  @LocalServerPort int port;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    EngineeringTest.update(
        "insert into platform.operator(id,login,password_hash,display_name) values (?,?,?,?)",
        OPERATOR_ID,
        OPERATOR_LOGIN,
        encoder.encode(OPERATOR_PASSWORD),
        "运营甲");
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect) values (?,'TENANT_READ','ALLOW'),"
            + "(?,'TENANT_CREATE','ALLOW')",
        OPERATOR_ID,
        OPERATOR_ID);
    EngineeringTest.update("insert into identity.tenant(id,name) values (?, '平台隔离机构')", TENANT);
    // 租户侧只要能通过“组织读取”校验即可证明会话有效，不引入业务数据。
    var tenantSchool = UUID.randomUUID();
    var tenantAccount = UUID.randomUUID();
    EngineeringTest.update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','隔离学校')",
        tenantSchool,
        TENANT,
        tenantSchool);
    EngineeringTest.update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        tenantAccount,
        TENANT,
        TENANT_LOGIN,
        encoder.encode(TENANT_PASSWORD));
    EngineeringTest.update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'ORGANIZATION_READ','ALLOW')",
        TENANT,
        tenantAccount,
        tenantSchool);
  }

  @Test
  void operatorLogsInAndSeesOwnActions() throws Exception {
    var client = client();
    var csrf = platformCsrf(client);
    var login =
        postForm(
            client,
            "/api/platform/session/login",
            csrf,
            "login=" + OPERATOR_LOGIN + "&password=" + OPERATOR_PASSWORD);
    assertThat(login.statusCode()).isEqualTo(204);
    assertThat(login.headers().allValues("set-cookie").toString())
        .contains("TS_PLATFORM_SESSION")
        .contains("HttpOnly");

    var me = get(client, "/api/platform/session/me");
    assertThat(me.statusCode()).isEqualTo(200);
    var body = new ObjectMapper().readTree(me.body());
    assertThat(body.get("login").asText()).isEqualTo(OPERATOR_LOGIN);
    assertThat(body.get("actions").toString()).contains("TENANT_READ").contains("TENANT_CREATE");
  }

  @Test
  void wrongOrUnknownCredentialsLookTheSame() throws Exception {
    var client = client();
    var csrf = platformCsrf(client);
    var wrong =
        postForm(
            client,
            "/api/platform/session/login",
            csrf,
            "login=" + OPERATOR_LOGIN + "&password=not-the-password");
    var unknown =
        postForm(
            client,
            "/api/platform/session/login",
            csrf,
            "login=no-such-operator&password=not-the-password");
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(unknown.statusCode()).isEqualTo(401);
    assertThat(wrong.body()).contains("PLATFORM_AUTHENTICATION_FAILED");
    assertThat(unknown.body()).contains("PLATFORM_AUTHENTICATION_FAILED");
  }

  @Test
  void platformSessionCannotReachTenantEndpoints() throws Exception {
    var client = client();
    var csrf = platformCsrf(client);
    assertThat(
            postForm(
                    client,
                    "/api/platform/session/login",
                    csrf,
                    "login=" + OPERATOR_LOGIN + "&password=" + OPERATOR_PASSWORD)
                .statusCode())
        .isEqualTo(204);
    assertThat(get(client, "/api/session/me").statusCode()).isEqualTo(401);
    assertThat(get(client, "/api/organization/units").statusCode()).isEqualTo(401);
  }

  @Test
  void tenantSessionCannotReachPlatformEndpoints() throws Exception {
    var client = client();
    var csrf = new ObjectMapper().readTree(get(client, "/api/session/csrf").body());
    var body =
        "username="
            + TENANT_LOGIN
            + "&password="
            + TENANT_PASSWORD
            + "&"
            + csrf.get("parameterName").asText()
            + "="
            + URLEncoder.encode(
                csrf.get("token").asText(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(postForm(client, "/api/session/login", null, body).statusCode()).isEqualTo(204);
    assertThat(get(client, "/api/session/me").statusCode()).isEqualTo(200);
    assertThat(get(client, "/api/platform/session/me").statusCode()).isEqualTo(401);
  }

  @Test
  void expiredSessionIsRejected() throws Exception {
    var client = client();
    var csrf = platformCsrf(client);
    assertThat(
            postForm(
                    client,
                    "/api/platform/session/login",
                    csrf,
                    "login=" + OPERATOR_LOGIN + "&password=" + OPERATOR_PASSWORD)
                .statusCode())
        .isEqualTo(204);
    EngineeringTest.update(
        "update platform.operator_session set expires_at = now() - interval '1 minute'"
            + " where operator_id=?",
        OPERATOR_ID);
    assertThat(get(client, "/api/platform/session/me").statusCode()).isEqualTo(401);
  }

  /** 数据库层面证明平台角色读不到业务表：应用层写错也越不过去。 */
  @Test
  void platformRoleCannotReadBusinessTables() throws Exception {
    try (var connection =
        java.sql.DriverManager.getConnection(
            System.getenv("TS_JDBC_URL"), "ts_platform", System.getenv("TS_PLATFORM_PASSWORD"))) {
      try (var statement = connection.createStatement()) {
        statement.executeQuery("select count(*) from teaching.class").close();
        fail("平台角色不应能读 teaching.class");
      } catch (SQLException denied) {
        assertThat(denied.getSQLState()).isEqualTo("42501");
      }
      try (var statement = connection.createStatement()) {
        statement.executeQuery("select count(*) from identity.tenant").close();
      }
    }
  }

  private HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String[] platformCsrf(HttpClient client) throws Exception {
    var response = get(client, "/api/platform/session/csrf");
    assertThat(response.statusCode()).isEqualTo(200);
    var node = new ObjectMapper().readTree(response.body());
    return new String[] {node.get("headerName").asText(), node.get("token").asText()};
  }

  private HttpResponse<String> postForm(HttpClient client, String path, String[] csrf, String body)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (csrf != null) builder.header(csrf[0], csrf[1]);
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
