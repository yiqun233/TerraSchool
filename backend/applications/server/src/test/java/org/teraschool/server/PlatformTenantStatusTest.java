package org.teraschool.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 平台停用/恢复租户：只影响登录与访问，数据保留；重复调用幂等；权限与不存在的租户都被拒绝。
 *
 * <p>最关键的一条是 {@link #suspendedTenantCannotLogIn()}：停用必须立刻挡住登录， 而不是等某个后台任务清理会话。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformTenantStatusTest {
  static final UUID OPERATOR_ID = UUID.randomUUID();
  static final String OPERATOR_LOGIN = "status-" + OPERATOR_ID.toString().substring(0, 8);
  static final String OPERATOR_PASSWORD = "Platform-Pass-" + UUID.randomUUID();
  static final UUID READONLY_ID = UUID.randomUUID();
  static final String READONLY_LOGIN = "readonly-" + READONLY_ID.toString().substring(0, 8);
  static final String ADMIN_PASSWORD =
      "Tenant-Admin-" + UUID.randomUUID().toString().substring(0, 8);

  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper();

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
        "状态运营");
    EngineeringTest.update(
        "insert into platform.operator(id,login,password_hash,display_name) values (?,?,?,?)",
        READONLY_ID,
        READONLY_LOGIN,
        encoder.encode(OPERATOR_PASSWORD),
        "只读运营");
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect) values"
            + " (?,'TENANT_READ','ALLOW'),(?,'TENANT_CREATE','ALLOW'),"
            + " (?,'TENANT_SUSPEND','ALLOW'),(?,'TENANT_RESUME','ALLOW')",
        OPERATOR_ID,
        OPERATOR_ID,
        OPERATOR_ID,
        OPERATOR_ID);
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect) values"
            + " (?,'TENANT_READ','ALLOW')",
        READONLY_ID);
  }

  @Test
  void suspendAndResumeChangeStateAndAuditOnce() throws Exception {
    var client = platformClient(OPERATOR_LOGIN);
    var tenantId = provisionTenant(client);

    assertThat(setStatus(client, tenantId, "suspend").statusCode()).isEqualTo(204);
    assertThat(active(tenantId)).isFalse();
    assertThat(
            count(
                "select count(*) from platform.audit_event where tenant_id=? and action=?",
                tenantId,
                "TENANT_SUSPENDED"))
        .isEqualTo(1);

    // 重复停用：状态未变，幂等返回且不重复写审计。
    assertThat(setStatus(client, tenantId, "suspend").statusCode()).isEqualTo(204);
    assertThat(
            count(
                "select count(*) from platform.audit_event where tenant_id=? and action=?",
                tenantId,
                "TENANT_SUSPENDED"))
        .isEqualTo(1);

    assertThat(setStatus(client, tenantId, "resume").statusCode()).isEqualTo(204);
    assertThat(active(tenantId)).isTrue();
    assertThat(
            count(
                "select count(*) from platform.audit_event where tenant_id=? and action=?",
                tenantId,
                "TENANT_RESUMED"))
        .isEqualTo(1);
  }

  @Test
  void suspendedTenantCannotLogIn() throws Exception {
    var platform = platformClient(OPERATOR_LOGIN);
    var admin = "adm-" + UUID.randomUUID().toString().substring(0, 8);
    var tenantId = provisionTenant(platform, admin);

    var tenant = client();
    assertThat(tenantLogin(tenant, admin)).isEqualTo(204);

    assertThat(setStatus(platform, tenantId, "suspend").statusCode()).isEqualTo(204);
    assertThat(tenantLogin(client(), admin)).isEqualTo(401);

    assertThat(setStatus(platform, tenantId, "resume").statusCode()).isEqualTo(204);
    assertThat(tenantLogin(client(), admin)).isEqualTo(204);
  }

  @Test
  void missingPermissionOrUnknownTenantIsRejected() throws Exception {
    var readonly = platformClient(READONLY_LOGIN);
    var tenantId = provisionTenant(platformClient(OPERATOR_LOGIN));

    assertThat(setStatus(readonly, tenantId, "suspend").statusCode()).isEqualTo(403);
    assertThat(active(tenantId)).isTrue();

    var operator = platformClient(OPERATOR_LOGIN);
    var unknown = setStatus(operator, UUID.randomUUID(), "suspend");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.body()).contains("TENANT_NOT_FOUND");
  }

  // ---------------------------------------------------------------- helpers

  private UUID provisionTenant(HttpClient client) throws Exception {
    return provisionTenant(client, "adm-" + UUID.randomUUID().toString().substring(0, 8));
  }

  private UUID provisionTenant(HttpClient client, String admin) throws Exception {
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    var payload =
        json.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("name", "状态机构-" + UUID.randomUUID().toString().substring(0, 6))
            .put("adminLogin", admin)
            .put("adminPassword", ADMIN_PASSWORD)
            .toString();
    var response =
        postJson(
            client,
            "/api/platform/tenants",
            csrf.get("headerName").asText(),
            csrf.get("token").asText(),
            payload);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return UUID.fromString(json.readTree(response.body()).get("tenantId").asText());
  }

  private HttpResponse<String> setStatus(HttpClient client, UUID tenantId, String action)
      throws Exception {
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    return client.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/platform/tenants/"
                        + tenantId
                        + "/"
                        + action))
            .header(csrf.get("headerName").asText(), csrf.get("token").asText())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private int tenantLogin(HttpClient client, String login) throws Exception {
    var csrf = json.readTree(get(client, "/api/session/csrf").body());
    var body =
        "username="
            + URLEncoder.encode(login, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(ADMIN_PASSWORD, StandardCharsets.UTF_8);
    return postForm(
            client,
            "/api/session/login",
            new String[] {csrf.get("headerName").asText(), csrf.get("token").asText()},
            body)
        .statusCode();
  }

  private HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  private HttpClient platformClient(String login) throws Exception {
    var client = client();
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    var body =
        "login="
            + login
            + "&password="
            + URLEncoder.encode(OPERATOR_PASSWORD, StandardCharsets.UTF_8);
    var response =
        postForm(
            client,
            "/api/platform/session/login",
            new String[] {csrf.get("headerName").asText(), csrf.get("token").asText()},
            body);
    assertThat(response.statusCode()).isEqualTo(204);
    return client;
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
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

  private HttpResponse<String> postJson(
      HttpClient client, String path, String header, String token, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header(header, token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static boolean active(UUID tenantId) throws Exception {
    return count("select count(*) from identity.tenant where id=? and active", tenantId) == 1;
  }

  private static int count(String sql, Object... args) throws Exception {
    try (var connection = EngineeringTest.admin();
        var statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
