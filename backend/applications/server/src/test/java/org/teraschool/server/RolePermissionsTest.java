package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RolePermissionsTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      FOREIGN = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();
  UUID user;
  @Autowired CurrentActor actors;
  @Autowired OrganizationQueries queries;
  @Autowired OrganizationCommands commands;

  @Autowired
  @Qualifier("identityDataSource")
  javax.sql.DataSource identity;

  @LocalServerPort int port;
  final JsonMapper json = JsonMapper.builder().build();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,'角色机构'),(?,'隔离机构')", TENANT, OTHER);
    for (UUID id : List.of(A, B, FOREIGN))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','角色学校')",
          id,
          id.equals(FOREIGN) ? OTHER : TENANT,
          id);
  }

  @BeforeEach
  void account() throws Exception {
    user = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        user,
        TENANT,
        user.toString(),
        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  UUID role(String... rules) throws Exception {
    UUID id = UUID.randomUUID();
    update(
        "insert into identity.permission_role(id,tenant_id,code,name) values (?,?,?,'角色模板')",
        id,
        TENANT,
        id.toString());
    for (String rule : rules) {
      String[] parts = rule.split(":");
      update(
          "insert into identity.role_action(tenant_id,role_id,action,effect) values (?,?,?,?)",
          TENANT,
          id,
          parts[0],
          parts[1]);
    }
    return id;
  }

  void assign(UUID role, UUID school) throws Exception {
    update(
        "insert into identity.account_role(tenant_id,account_id,role_id,school_id) values (?,?,?,?)",
        TENANT,
        user,
        role,
        school);
  }

  void direct(String action, UUID school, String effect) throws Exception {
    update(
        "insert into identity.account_permission(tenant_id,account_id,action,school_id,effect) values (?,?,?,?,?)",
        TENANT,
        user,
        action,
        school,
        effect);
  }

  @Test
  void noGrantDeniesByDefault() {
    assertThat(actors.permissions().canReadOrganizations()).isFalse();
    assertThat(actors.permissions().readableSchoolIds()).isEmpty();
    assertThatThrownBy(() -> queries.list(0, 20))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void rolesAndAccountAllowKeepActionScopesSeparate() throws Exception {
    UUID maintainer = role("ORGANIZATION_READ:ALLOW", "ORGANIZATION_MAINTAIN:ALLOW");
    assign(maintainer, A);
    direct("ORGANIZATION_READ", B, "ALLOW");
    var p = actors.permissions();
    assertThat(p.readableSchoolIds()).containsExactlyInAnyOrder(A, B);
    assertThat(p.maintainableSchoolIds()).containsExactly(A);
    assertThatThrownBy(
            () ->
                commands.createChild(
                    new CreateOrganizationUnit(UUID.randomUUID(), B, "CAMPUS", "不应新增")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void accountDenyOverridesRoleAllowAndRoleDenyOverridesAccountAllow() throws Exception {
    assign(role("ORGANIZATION_READ:ALLOW", "ORGANIZATION_MAINTAIN:ALLOW"), A);
    direct("ORGANIZATION_MAINTAIN", A, "DENY");
    assertThat(actors.permissions().maintainableSchoolIds()).isEmpty();
    update("delete from identity.account_permission where account_id=? and effect='DENY'", user);
    direct("ORGANIZATION_MAINTAIN", A, "ALLOW");
    assign(role("ORGANIZATION_MAINTAIN:DENY"), A);
    assertThat(actors.permissions().maintainableSchoolIds()).isEmpty();
    assign(role("ORGANIZATION_READ:ALLOW", "ORGANIZATION_MAINTAIN:ALLOW"), B);
    direct("ORGANIZATION_MAINTAIN", null, "DENY");
    assertThat(actors.permissions().canReadOrganizations()).isTrue();
    assertThat(actors.permissions().canMaintainOrganizations()).isFalse();
    assertThat(actors.permissions().maintainableSchoolIds()).isEmpty();
  }

  @Test
  void functionAndSchoolDenyDoNotBecomeOtherActionAllow() throws Exception {
    UUID role = role("ORGANIZATION_READ:ALLOW", "ORGANIZATION_MAINTAIN:ALLOW");
    assign(role, A);
    assign(role, B);
    direct("ORGANIZATION_READ", null, "DENY");
    assertThat(actors.permissions().canReadOrganizations()).isFalse();
    assertThat(actors.permissions().maintainableSchoolIds()).isEmpty();
    update("delete from identity.account_permission where account_id=?", user);
    direct("ORGANIZATION_READ", A, "DENY");
    assertThat(actors.permissions().readableSchoolIds()).containsExactly(B);
    assertThat(actors.permissions().maintainableSchoolIds()).containsExactly(B);
  }

  @Test
  void functionalAllowWithoutSchoolNeverMeansAllSchools() throws Exception {
    direct("ORGANIZATION_READ", null, "ALLOW");
    direct("ORGANIZATION_MAINTAIN", null, "ALLOW");
    assertThat(actors.permissions().canReadOrganizations()).isTrue();
    assertThat(queries.list(0, 20).total()).isZero();
    assertThat(actors.permissions().maintainableSchoolIds()).isEmpty();
  }

  @Test
  void roleDisableAndMembershipRemovalApplyImmediately() throws Exception {
    UUID role = role("ORGANIZATION_READ:ALLOW");
    assign(role, A);
    assertThat(queries.list(0, 20).total()).isEqualTo(1);
    update("update identity.permission_role set active=false where id=?", role);
    assertThat(actors.permissions().canReadOrganizations()).isFalse();
    update("update identity.permission_role set active=true where id=?", role);
    update("delete from identity.account_role where account_id=?", user);
    assertThat(actors.permissions().canReadOrganizations()).isFalse();
  }

  @Test
  void crossTenantRoleSchoolAndOverrideFailAtDatabase() throws Exception {
    UUID role = role("ORGANIZATION_READ:ALLOW"), foreignRole = UUID.randomUUID();
    update(
        "insert into identity.permission_role(id,tenant_id,code,name) values (?,?,'foreign','外部角色')",
        foreignRole,
        OTHER);
    assertThatThrownBy(() -> assign(foreignRole, A)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> assign(role, FOREIGN)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> direct("ORGANIZATION_READ", FOREIGN, "ALLOW"))
        .isInstanceOf(SQLException.class);
    UUID campus = UUID.randomUUID();
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'CAMPUS','不是学校')",
        campus,
        TENANT,
        A);
    assertThatThrownBy(() -> assign(role, campus)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> direct("ORGANIZATION_READ", campus, "ALLOW"))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void identityConnectionCannotManageRolesOrPermissions() throws Exception {
    try (var c = identity.getConnection();
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into identity.permission_role(tenant_id,code,name) values ('"
                          + TENANT
                          + "','bad','不应创建')"))
          .isInstanceOf(SQLException.class);
    }
  }

  HttpResponse<String> get(HttpClient c, String path) throws Exception {
    return c.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void existingHttpSessionUsesCurrentRolesAndAccountDeny() throws Exception {
    UUID role = role("ORGANIZATION_READ:ALLOW");
    assign(role, A);
    var c =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    assertThat(get(c, "/api/session/permissions").statusCode()).isEqualTo(401);
    String token = json.readTree(get(c, "/api/session/csrf").body()).get("token").asText();
    var login =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=" + PASSWORD))
            .build();
    assertThat(c.send(login, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
    // 接口不接受目标账号，即使夹带参数也只返回可信会话本人。
    assertThat(
            json.readTree(get(c, "/api/session/permissions?accountId=" + FOREIGN).body())
                .get("accountId")
                .asText())
        .isEqualTo(user.toString());
    assertThat(
            json.readTree(get(c, "/api/session/permissions").body())
                .get("readableSchoolIds")
                .get(0)
                .asText())
        .isEqualTo(A.toString());
    direct("ORGANIZATION_READ", null, "DENY");
    assertThat(get(c, "/api/organization/units").statusCode()).isEqualTo(403);
    assertThat(
            json.readTree(get(c, "/api/session/permissions").body())
                .get("canReadOrganizations")
                .asBoolean())
        .isFalse();
    update("delete from identity.account_permission where account_id=?", user);
    update("update identity.permission_role set active=false where id=?", role);
    assertThat(get(c, "/api/organization/units").statusCode()).isEqualTo(403);
  }
}
