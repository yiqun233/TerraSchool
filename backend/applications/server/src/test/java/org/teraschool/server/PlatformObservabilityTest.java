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
 * 平台运营可观测（批次 A）：总览、开通记录、审计日志。
 *
 * <p>这组用例要证明三件事：三段查询只输出平台可见信息（没有业务字段与凭据）；三个入口各自 校验自己的动作（能看租户不等于能看开通记录或审计）；开通失败与停用动作都留痕且可筛选。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformObservabilityTest {
  static final UUID FULL_ID = UUID.randomUUID();
  static final String FULL_LOGIN = "obs-full-" + FULL_ID.toString().substring(0, 8);
  static final UUID VIEWER_ID = UUID.randomUUID();
  static final String VIEWER_LOGIN = "obs-view-" + VIEWER_ID.toString().substring(0, 8);
  static final UUID CREATOR_ID = UUID.randomUUID();
  static final String CREATOR_LOGIN = "obs-create-" + CREATOR_ID.toString().substring(0, 8);
  static final String PASSWORD = "Platform-Pass-" + UUID.randomUUID();
  static final String ADMIN_PASSWORD =
      "Tenant-Admin-" + UUID.randomUUID().toString().substring(0, 8);
  static final UUID SEEDED_TENANT = UUID.randomUUID();
  static final UUID SEEDED_SCHOOL = UUID.randomUUID();

  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    for (var operator :
        List.of(
            new Object[] {FULL_ID, FULL_LOGIN, "全权运营"},
            new Object[] {VIEWER_ID, VIEWER_LOGIN, "只读运营"},
            new Object[] {CREATOR_ID, CREATOR_LOGIN, "开通运营"})) {
      EngineeringTest.update(
          "insert into platform.operator(id,login,password_hash,display_name) values (?,?,?,?)",
          operator[0],
          operator[1],
          encoder.encode(PASSWORD),
          operator[2]);
    }
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect) values"
            + " (?,'TENANT_READ','ALLOW'),(?,'TENANT_CREATE','ALLOW'),"
            + " (?,'TENANT_SUSPEND','ALLOW'),(?,'TENANT_RESUME','ALLOW'),"
            + " (?,'PROVISIONING_READ','ALLOW'),(?,'PLATFORM_AUDIT_READ','ALLOW')",
        FULL_ID,
        FULL_ID,
        FULL_ID,
        FULL_ID,
        FULL_ID,
        FULL_ID);
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect) values"
            + " (?,'TENANT_READ','ALLOW')",
        VIEWER_ID);
    // 能开通但不能看开通记录：服务端必须按动作拒绝，而不是靠前端藏按钮。
    EngineeringTest.update(
        "insert into platform.operator_permission(operator_id,action,effect) values"
            + " (?,'TENANT_READ','ALLOW'),(?,'TENANT_CREATE','ALLOW')",
        CREATOR_ID,
        CREATOR_ID);

    EngineeringTest.update(
        "insert into identity.tenant(id,name) values (?, '可观测种子机构')", SEEDED_TENANT);
    // organization.unit 要求 SCHOOL 的 id 与 school_id 相同（CHECK(kind<>'SCHOOL' OR id=school_id)）。
    EngineeringTest.update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name)"
            + " values (?,?,?,'SCHOOL','可观测种子学校')",
        SEEDED_SCHOOL,
        SEEDED_TENANT,
        SEEDED_SCHOOL);
  }

  @Test
  void overviewOnlyShowsPlatformVisibleCounts() throws Exception {
    var client = platformClient(FULL_LOGIN);
    var response = get(client, "/api/platform/overview");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

    var body = json.readTree(response.body());
    assertThat(body.get("tenants").get("total").asLong()).isGreaterThanOrEqualTo(1);
    assertThat(body.get("tenants").get("thisMonth").asLong()).isGreaterThanOrEqualTo(1);
    // 规模计数与数据库一致：平台看到的是数字，不是业务明细。
    assertThat(body.get("schools").asLong())
        .isEqualTo(count("select count(*) from organization.unit where kind='SCHOOL'"));
    assertThat(body.get("accounts").asLong())
        .isEqualTo(count("select count(*) from identity.account"));
    assertThat(body.get("recentProvisioning").isArray()).isTrue();
    assertThat(body.get("recentAudit").isArray()).isTrue();

    // 平台响应里不应出现业务对象名与凭据字段。
    for (var forbidden : List.of("学员", "student", "password", "password_hash", "amount")) {
      assertThat(response.body()).as("总览不应包含 %s", forbidden).doesNotContain(forbidden);
    }
  }

  @Test
  void overviewRequiresTenantReadAndHidesUnreadableSummaries() throws Exception {
    var anonymous = HttpClient.newHttpClient();
    assertThat(get(anonymous, "/api/platform/overview").statusCode()).isEqualTo(401);

    var viewer = platformClient(VIEWER_LOGIN);
    var body = json.readTree(get(viewer, "/api/platform/overview").body());
    // 只读运营没有开通/审计动作：摘要为空数组，而不是报错或越权返回内容。
    assertThat(body.get("recentProvisioning").size()).isZero();
    assertThat(body.get("recentAudit").size()).isZero();
  }

  @Test
  void failedProvisioningLeavesReadableRecordWithParameters() throws Exception {
    var creator = platformClient(CREATOR_LOGIN);
    var admin = "obs-dup-" + UUID.randomUUID().toString().substring(0, 8);
    provisionTenant(creator, admin);

    var failedName = "失败机构-" + UUID.randomUUID().toString().substring(0, 6);
    var failed = provision(creator, failedName, admin);
    assertThat(failed.statusCode()).as(failed.body()).isEqualTo(409);
    assertThat(failed.body()).contains("ADMIN_LOGIN_TAKEN");

    // 开通记录页靠这两列解释“谁失败了”，失败行本身没有 tenant_id。
    var records =
        json.readTree(
            get(
                    platformClient(FULL_LOGIN),
                    "/api/platform/provisioning-requests?status=FAILED&keyword="
                        + URLEncoder.encode(failedName, StandardCharsets.UTF_8))
                .body());
    assertThat(records.get("total").asLong()).isEqualTo(1);
    var row = records.get("items").get(0);
    assertThat(row.get("status").asText()).isEqualTo("FAILED");
    assertThat(row.get("error_code").asText()).isEqualTo("ADMIN_LOGIN_TAKEN");
    assertThat(row.get("tenant_name").asText()).isEqualTo(failedName);
    assertThat(row.get("admin_login").asText()).isEqualTo(admin);
    assertThat(row.get("operator_login").asText()).isEqualTo(CREATOR_LOGIN);
  }

  @Test
  void provisioningRecordsRequireTheirOwnAction() throws Exception {
    // 有开通权限但没有 PROVISIONING_READ：列表被拒绝。
    assertThat(
            get(platformClient(CREATOR_LOGIN), "/api/platform/provisioning-requests").statusCode())
        .isEqualTo(403);
    assertThat(
            get(platformClient(FULL_LOGIN), "/api/platform/provisioning-requests?size=0")
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void auditShowsSuspendWithOperatorAndTenantAndSupportsFilters() throws Exception {
    var full = platformClient(FULL_LOGIN);
    var tenantId =
        provisionTenant(full, "obs-audit-" + UUID.randomUUID().toString().substring(0, 8));
    assertThat(setStatus(full, tenantId, "suspend").statusCode()).isEqualTo(204);

    var events =
        json.readTree(
            get(full, "/api/platform/audit-events?action=TENANT_SUSPENDED&tenantId=" + tenantId)
                .body());
    assertThat(events.get("total").asLong()).isEqualTo(1);
    var event = events.get("items").get(0);
    assertThat(event.get("action").asText()).isEqualTo("TENANT_SUSPENDED");
    assertThat(event.get("tenant_id").asText()).isEqualTo(tenantId.toString());
    assertThat(event.get("operator_login").asText()).isEqualTo(FULL_LOGIN);
    assertThat(event.get("changed_fields").asText()).contains("active=true->false");
    // 审计只读展示字段：不能带出凭据列。
    assertThat(events.toString()).doesNotContain("password");

    // 换一个租户筛选不到这条；按不存在的操作者同样为空。
    var otherTenant =
        json.readTree(
            get(
                    full,
                    "/api/platform/audit-events?action=TENANT_SUSPENDED&tenantId="
                        + UUID.randomUUID())
                .body());
    assertThat(otherTenant.get("total").asLong()).isZero();
    var otherOperator =
        json.readTree(
            get(
                    full,
                    "/api/platform/audit-events?action=TENANT_SUSPENDED&operator=no-such-operator")
                .body());
    assertThat(otherOperator.get("total").asLong()).isZero();
  }

  @Test
  void auditRequiresItsOwnAction() throws Exception {
    // 只读运营能看租户，但没有 PLATFORM_AUDIT_READ。
    assertThat(get(platformClient(VIEWER_LOGIN), "/api/platform/audit-events").statusCode())
        .isEqualTo(403);
  }

  @Test
  void auditRejectsInvalidFilters() throws Exception {
    var full = platformClient(FULL_LOGIN);
    assertThat(get(full, "/api/platform/audit-events?tenantId=not-a-uuid").statusCode())
        .isEqualTo(400);
    assertThat(
            get(
                    full,
                    "/api/platform/audit-events"
                        + "?from=2026-11-14T00:00:00Z&to=2026-11-13T00:00:00Z")
                .statusCode())
        .isEqualTo(400);
    assertThat(get(full, "/api/platform/audit-events?from=yesterday").statusCode()).isEqualTo(400);
  }

  // ---------------------------------------------------------------- helpers

  private UUID provisionTenant(HttpClient client, String admin) throws Exception {
    var name = "可观测机构-" + UUID.randomUUID().toString().substring(0, 6);
    var response = provision(client, name, admin);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return UUID.fromString(json.readTree(response.body()).get("tenantId").asText());
  }

  private HttpResponse<String> provision(HttpClient client, String name, String admin)
      throws Exception {
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    var payload =
        json.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("name", name)
            .put("adminLogin", admin)
            .put("adminPassword", ADMIN_PASSWORD)
            .toString();
    return postJson(
        client,
        "/api/platform/tenants",
        csrf.get("headerName").asText(),
        csrf.get("token").asText(),
        payload);
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

  private HttpClient platformClient(String login) throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var csrf = json.readTree(get(client, "/api/platform/session/csrf").body());
    var body =
        "login=" + login + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
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
