package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 租户开通：一次平台操作产出可登录的租户与管理员，且不建学校（DEC-024）。
 *
 * <p>最关键的一条是 {@link #adminWithoutAnySchoolCanCreateTheFirstOne}：
 * 现有“覆盖全部学校”的权限判断在零学校时会返回否，新租户曾经连第一所学校都建不出来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantProvisioningTest {
  static final UUID OPERATOR_ID = UUID.randomUUID();
  static final String OPERATOR_LOGIN = "prov-" + OPERATOR_ID.toString().substring(0, 8);
  static final String OPERATOR_PASSWORD = "Platform-Pass-" + UUID.randomUUID();
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
    EngineeringTest.update(
        "insert into platform.operator(id,login,password_hash,display_name) values (?,?,?,?)",
        OPERATOR_ID,
        OPERATOR_LOGIN,
        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(OPERATOR_PASSWORD),
        "开通运营");
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect)"
            + " values (?,'TENANT_READ','ALLOW'),(?,'TENANT_CREATE','ALLOW')",
        OPERATOR_ID,
        OPERATOR_ID);
  }

  @Test
  void provisioningCreatesTenantAndAdminButNoSchool() throws Exception {
    var client = platformClient();
    var name = "开通机构-" + UUID.randomUUID().toString().substring(0, 6);
    var admin = "adm-" + UUID.randomUUID().toString().substring(0, 8);

    var response = provision(client, UUID.randomUUID(), name, admin);
    assertThat(response.statusCode()).isEqualTo(201);
    var body = json.readTree(response.body());
    var tenantId = UUID.fromString(body.get("tenantId").asText());
    var accountId = UUID.fromString(body.get("accountId").asText());

    assertThat(count("select count(*) from identity.tenant where id=?", tenantId)).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from identity.account where id=? and tenant_admin and"
                    + " password_change_required and active",
                accountId))
        .isEqualTo(1);
    // 开通不建学校：客户登录后自己建。
    assertThat(count("select count(*) from organization.unit where tenant_id=?", tenantId))
        .isEqualTo(0);
    // 幂等记录与平台审计都留痕。
    assertThat(
            count(
                "select count(*) from platform.provisioning_request where tenant_id=? and status='ACTIVE'",
                tenantId))
        .isEqualTo(1);
    assertThat(count("select count(*) from platform.audit_event where tenant_id=?", tenantId))
        .isEqualTo(1);
  }

  @Test
  void sameRequestIsIdempotentAndChangedParametersAreRejected() throws Exception {
    var client = platformClient();
    var requestId = UUID.randomUUID();
    var name = "幂等机构-" + UUID.randomUUID().toString().substring(0, 6);
    var admin = "idem-" + UUID.randomUUID().toString().substring(0, 8);

    var first = provision(client, requestId, name, admin);
    var second = provision(client, requestId, name, admin);
    assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(json.readTree(second.body()).get("tenantId").asText())
        .isEqualTo(json.readTree(first.body()).get("tenantId").asText());
    assertThat(count("select count(*) from identity.tenant where name=?", name)).isEqualTo(1);

    var changed = provision(client, requestId, name + "-改名", admin);
    assertThat(changed.statusCode()).isEqualTo(409);
    assertThat(changed.body()).contains("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void duplicateNameOrLoginIsRejectedWithStableCodes() throws Exception {
    var client = platformClient();
    var name = "重名机构-" + UUID.randomUUID().toString().substring(0, 6);
    var admin = "dup-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(provision(client, UUID.randomUUID(), name, admin).statusCode()).isEqualTo(201);

    var sameName = provision(client, UUID.randomUUID(), name, admin + "x");
    assertThat(sameName.statusCode()).isEqualTo(409);
    assertThat(sameName.body()).contains("TENANT_NAME_TAKEN");

    var sameLogin = provision(client, UUID.randomUUID(), name + "-另一家", admin);
    assertThat(sameLogin.statusCode()).isEqualTo(409);
    assertThat(sameLogin.body()).contains("ADMIN_LOGIN_TAKEN");
  }

  @Test
  void weakPasswordIsRejectedBeforeAnythingIsWritten() throws Exception {
    var client = platformClient();
    var name = "弱口令机构-" + UUID.randomUUID().toString().substring(0, 6);
    var response =
        provision(
            client,
            UUID.randomUUID(),
            name,
            "weak-" + UUID.randomUUID().toString().substring(0, 6),
            "short");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("INVALID_REQUEST");
    assertThat(count("select count(*) from identity.tenant where name=?", name)).isEqualTo(0);
  }

  /** 零学校起步：新租户的管理员必须能建出第一所学校，否则开通没有意义。 */
  @Test
  void adminWithoutAnySchoolCanCreateTheFirstOne() throws Exception {
    var platform = platformClient();
    var name = "首校机构-" + UUID.randomUUID().toString().substring(0, 6);
    var admin = "first-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(provision(platform, UUID.randomUUID(), name, admin).statusCode()).isEqualTo(201);

    var tenant = client();
    var sessionCsrf = json.readTree(get(tenant, "/api/session/csrf").body());
    var form =
        "username="
            + admin
            + "&password="
            + URLEncoder.encode(ADMIN_PASSWORD, java.nio.charset.StandardCharsets.UTF_8)
            + "&"
            + sessionCsrf.get("parameterName").asText()
            + "="
            + URLEncoder.encode(
                sessionCsrf.get("token").asText(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(postForm(tenant, "/api/session/login", null, form).statusCode()).isEqualTo(204);

    var organizationCsrf = json.readTree(get(tenant, "/api/session/csrf").body());
    var payload =
        json.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("name", "客户首校")
            .toString();
    var created =
        postJson(
            tenant,
            "/api/organization/schools",
            organizationCsrf.get("headerName").asText(),
            organizationCsrf.get("token").asText(),
            payload);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(
            count(
                "select count(*) from organization.unit u join identity.account a"
                    + " on a.tenant_id=u.tenant_id where a.login=? and u.kind='SCHOOL'",
                admin))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------- helpers

  private HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  private HttpClient platformClient() throws Exception {
    var client = client();
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    var body =
        "login="
            + OPERATOR_LOGIN
            + "&password="
            + URLEncoder.encode(OPERATOR_PASSWORD, java.nio.charset.StandardCharsets.UTF_8);
    var login =
        postForm(
            client,
            "/api/platform/session/login",
            new String[] {csrf.get("headerName").asText(), csrf.get("token").asText()},
            body);
    assertThat(login.statusCode()).isEqualTo(204);
    return client;
  }

  private HttpResponse<String> provision(
      HttpClient client, UUID requestId, String name, String admin) throws Exception {
    return provision(client, requestId, name, admin, ADMIN_PASSWORD);
  }

  private HttpResponse<String> provision(
      HttpClient client, UUID requestId, String name, String admin, String password)
      throws Exception {
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    var payload =
        json.createObjectNode()
            .put("requestId", requestId.toString())
            .put("name", name)
            .put("adminLogin", admin)
            .put("adminPassword", password)
            .toString();
    return postJson(
        client,
        "/api/platform/tenants",
        csrf.get("headerName").asText(),
        csrf.get("token").asText(),
        payload);
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
