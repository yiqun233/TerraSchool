package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.*;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.AuthorizationManagement.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationManagementTest {
  @Autowired AuthorizationManagement service;
  @Autowired CurrentActor actors;
  @Autowired TenantTransactions tx;
  @Autowired DSLContext sql;
  @LocalServerPort int port;
  UUID tenant, other, a, b, foreignSchool, root, delegate, target, foreignUser;
  static final String PASSWORD = UUID.randomUUID().toString();
  final JsonMapper json = JsonMapper.builder().build();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  void as(UUID id) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                EngineeringTest.principal(id), null, List.of()));
  }

  UUID user(UUID t) throws Exception {
    UUID id = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        id,
        t,
        id.toString(),
        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    return id;
  }

  void grant(UUID user, String action, UUID school, String effect) throws Exception {
    update(
        "insert into identity.account_permission(tenant_id,account_id,action,school_id,effect) values (?,?,?,?,?)",
        user.equals(foreignUser) ? other : tenant,
        user,
        action,
        school,
        effect);
  }

  @BeforeEach
  void seed() throws Exception {
    tenant = UUID.randomUUID();
    other = UUID.randomUUID();
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    foreignSchool = UUID.randomUUID();
    update("insert into identity.tenant(id,name) values (?,'授权机构'),(?,'外机构')", tenant, other);
    for (UUID school : List.of(a, b, foreignSchool))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','管理学校')",
          school,
          school.equals(foreignSchool) ? other : tenant,
          school);
    root = user(tenant);
    delegate = user(tenant);
    target = user(tenant);
    foreignUser = user(other);
    grant(root, "AUTHORIZATION_MANAGE", a, "ALLOW");
    grant(root, "AUTHORIZATION_MANAGE", b, "ALLOW");
    grant(delegate, "AUTHORIZATION_MANAGE", a, "ALLOW");
    grant(target, "ORGANIZATION_READ", a, "ALLOW");
    as(root);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  Replacement set(long version, Rule... rules) {
    return new Replacement(version, List.of(rules), List.of());
  }

  Rule allow(String action, UUID school) {
    return new Rule(action, school, "ALLOW");
  }

  UUID role(String action, UUID school) throws Exception {
    UUID id = UUID.randomUUID();
    update(
        "insert into identity.permission_role(id,tenant_id,code,name) values (?,?,?,'可分配角色')",
        id,
        tenant,
        id.toString());
    update(
        "insert into identity.role_action(tenant_id,role_id,action,effect) values (?,?,?,'ALLOW')",
        tenant,
        id,
        action);
    return id;
  }

  int count(String query, Object... args) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement(query)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      var r = s.executeQuery();
      r.next();
      return r.getInt(1);
    }
  }

  @Test
  void replacingEntireSetIsAuditedAndVersioned() throws Exception {
    var before = service.get(target);
    var after =
        service.replace(
            target,
            set(
                before.version(),
                allow("ORGANIZATION_READ", b),
                allow("ORGANIZATION_MAINTAIN", b)));
    assertThat(after.version()).isEqualTo(1);
    assertThat(after.effective().readableSchoolIds()).containsExactly(b);
    assertThat(after.effective().maintainableSchoolIds()).containsExactly(b);
    assertThat(
            count(
                "select count(*) from audit.authorization_event where tenant_id=? and target_account_id=? and previous_version=0 and next_version=1",
                tenant,
                target))
        .isEqualTo(1);
    assertThatThrownBy(() -> service.replace(target, set(0)))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("AUTHORIZATION_VERSION_CONFLICT");
    assertThat(service.get(target).rules()).hasSize(2);
  }

  @Test
  void nonManagerAndCrossTenantHaveNoAccess() {
    assertThatThrownBy(() -> service.get(foreignUser)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> service.replace(target, set(0, allow("ORGANIZATION_READ", foreignSchool))))
        .isInstanceOf(AccessDeniedException.class);
    as(target);
    assertThatThrownBy(() -> service.accounts("")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.replace(target, set(0)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void tenantAdminWithoutSchoolsCanListAccountsInsteadOfSqlError() throws Exception {
    // 平台开通只产出“机构 + 管理员”，不建学校：新机构管理员的管理范围为空。
    // 此时账号列表与可选学校必须正常返回，不能把空集合拼成 in () 触发语法错误（500）。
    var fresh = user(tenant);
    update("update identity.account set tenant_admin=true where id=?", fresh);
    as(fresh);
    assertThat(service.schools("")).isEmpty();
    assertThat(service.accounts(""))
        .extracting(Option::id)
        .contains(fresh)
        .doesNotContain(root, target);
  }

  @Test
  void delegationChecksOriginalAndFinalConfigurationIncludingMaskedAllow() throws Exception {
    grant(target, "ORGANIZATION_READ", b, "ALLOW");
    grant(target, "ORGANIZATION_READ", null, "DENY");
    as(delegate);
    assertThat(service.accounts(""))
        .extracting(Option::id)
        .doesNotContain(target, root, foreignUser);
    assertThatThrownBy(() -> service.get(target)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.replace(target, set(0)))
        .isInstanceOf(AccessDeniedException.class);
    update(
        "delete from identity.account_permission where account_id=? and (school_id=? or school_id is null)",
        target,
        b);
    assertThatThrownBy(() -> service.replace(target, set(0, allow("ORGANIZATION_READ", b))))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            service
                .replace(target, set(0, allow("ORGANIZATION_READ", a)))
                .effective()
                .readableSchoolIds())
        .containsExactly(a);
  }

  @Test
  void roleAssignmentsUseSameEngineAndCannotEscapeSchoolOrTenant() throws Exception {
    UUID role = role("ORGANIZATION_READ", a);
    var changed =
        service.replace(
            target, new Replacement(0L, List.of(), List.of(new Assignment(role, b, 0L))));
    assertThat(changed.effective().readableSchoolIds()).containsExactly(b);
    as(delegate);
    assertThatThrownBy(() -> service.get(target)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                service.replace(
                    delegate, new Replacement(0L, List.of(), List.of(new Assignment(role, b, 0L)))))
        .isInstanceOf(AccessDeniedException.class);
    as(root);
    update("update identity.permission_role set active=false where id=?", role);
    assertThatThrownBy(
            () ->
                service.replace(
                    target, new Replacement(1L, List.of(), List.of(new Assignment(role, a, 1L)))))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void lastAdministratorCannotBeRemovedOrGloballyDenied() {
    assertThatThrownBy(() -> service.replace(root, set(0)))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("LAST_AUTHORIZATION_ADMINISTRATOR");
    var old = service.get(root);
    var rules = new ArrayList<>(old.rules());
    rules.add(new Rule("AUTHORIZATION_MANAGE", null, "DENY"));
    assertThatThrownBy(() -> service.replace(root, new Replacement(0L, rules, List.of())))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("LAST_AUTHORIZATION_ADMINISTRATOR");
    assertThat(service.get(root).version()).isZero();
    assertThat(service.get(root).effective().manageableSchoolIds()).containsExactlyInAnyOrder(a, b);
  }

  @Test
  void inactiveOrDeniedBackupIsNotAnEffectiveAdministrator() throws Exception {
    UUID backup = user(tenant);
    UUID adminRole = role("AUTHORIZATION_MANAGE", a);
    for (UUID school : List.of(a, b))
      update(
          "insert into identity.account_role(tenant_id,account_id,role_id,school_id) values (?,?,?,?)",
          tenant,
          backup,
          adminRole,
          school);
    update("update identity.account set active=false where id=?", backup);
    assertThatThrownBy(() -> service.replace(root, set(0)))
        .isInstanceOf(AuthorizationConflict.class);
    update("update identity.account set active=true where id=?", backup);
    grant(backup, "AUTHORIZATION_MANAGE", b, "DENY");
    assertThatThrownBy(() -> service.replace(root, set(0)))
        .isInstanceOf(AuthorizationConflict.class);
    update("delete from identity.account_permission where account_id=? and effect='DENY'", backup);
    update("update identity.permission_role set active=false where id=?", adminRole);
    assertThatThrownBy(() -> service.replace(root, set(0)))
        .isInstanceOf(AuthorizationConflict.class);
    update("update identity.permission_role set active=true where id=?", adminRole);
    assertThat(service.replace(root, set(0)).effective().manageableSchoolIds()).isEmpty();
    as(backup);
    assertThat(service.get(root).version()).isEqualTo(1);
  }

  @Test
  void competingWholeSetReplacementsNeverUnion() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<Boolean>>();
      for (UUID school : List.of(a, b))
        tasks.add(
            pool.submit(
                () -> {
                  as(root);
                  try {
                    start.await();
                    service.replace(target, set(0, allow("ORGANIZATION_READ", school)));
                    return true;
                  } catch (AuthorizationConflict conflict) {
                    return false;
                  } finally {
                    clear();
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var result : tasks) if (result.get(20, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      assertThat(service.get(target).rules()).hasSize(1);
      assertThat(service.get(target).version()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentAdministratorsCannotBothRevokeThemselves() throws Exception {
    UUID second = user(tenant);
    grant(second, "AUTHORIZATION_MANAGE", a, "ALLOW");
    grant(second, "AUTHORIZATION_MANAGE", b, "ALLOW");
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<Boolean>>();
      for (UUID who : List.of(root, second))
        tasks.add(
            pool.submit(
                () -> {
                  as(who);
                  try {
                    start.await();
                    service.replace(who, set(0));
                    return true;
                  } catch (AuthorizationConflict conflict) {
                    return false;
                  } finally {
                    clear();
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var result : tasks) if (result.get(20, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      as(root);
      boolean rootManages = actors.permissions().canManageAuthorizations();
      as(second);
      assertThat(rootManages ^ actors.permissions().canManageAuthorizations()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void queuedManagerIsRecheckedAfterRevocationCommits() throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holder =
          pool.submit(
              () -> {
                as(root);
                try {
                  return tx.execute(
                      tenant,
                      () -> {
                        service.replace(delegate, set(0));
                        locked.countDown();
                        try {
                          release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return true;
                      });
                } finally {
                  clear();
                }
              });
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      var queued =
          pool.submit(
              () -> {
                as(delegate);
                try {
                  service.replace(target, set(0));
                  return false;
                } catch (AccessDeniedException denied) {
                  return true;
                } finally {
                  clear();
                }
              });
      boolean waiting = false;
      for (int attempt = 0; attempt < 100; attempt++) {
        if (count(
                "select count(*) from pg_stat_activity where usename='ts_app' and wait_event='advisory' and query like '%pg_advisory_xact_lock%'")
            > 0) {
          waiting = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(waiting).as("第二请求确实在数据库授权锁上排队").isTrue();
      release.countDown();
      assertThat(holder.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(queued.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(service.get(target).version()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void successAndAuditRollBackTogetherAndFailureSurvives() throws Exception {
    assertThatThrownBy(
            () ->
                tx.execute(
                    tenant,
                    () -> {
                      service.replace(target, set(0));
                      throw new IllegalStateException("outer");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.get(target).version()).isZero();
    assertThat(count("select count(*) from audit.authorization_event where tenant_id=?", tenant))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and action='AUTHORIZATION_REPLACE' and code='TRANSACTION_ROLLED_BACK'",
                tenant))
        .isEqualTo(1);
    assertThatThrownBy(() -> service.replace(root, set(0)))
        .isInstanceOf(AuthorizationConflict.class);
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and code='LAST_AUTHORIZATION_ADMINISTRATOR'",
                tenant))
        .isEqualTo(1);
  }

  @Test
  void runtimeSqlHasTenantRlsAndCannotReadPasswordsOrChangeLogin() {
    assertThat(sql.fetch("select id from identity.account")).isEmpty();
    tx.execute(
        tenant,
        () -> {
          assertThat(sql.fetch("select id from identity.account")).hasSize(3);
          assertThat(sql.fetch("select id from identity.account where id=?", foreignUser))
              .isEmpty();
          return null;
        });
    assertThatThrownBy(
            () -> tx.execute(tenant, () -> sql.fetch("select password_hash from identity.account")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    tenant,
                    () ->
                        sql.execute(
                            "update identity.account set login='forbidden' where id=?", root)))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    tenant,
                    () ->
                        sql.execute(
                            "insert into identity.account_permission(tenant_id,account_id,action,effect) values (?,?,'ORGANIZATION_READ','ALLOW')",
                            other,
                            foreignUser)))
        .isInstanceOf(RuntimeException.class);
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpRequiresCsrfAndStrictInputAndUpdatesSameSession() throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var login =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString("username=" + root + "&password=" + PASSWORD))
            .build();
    assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(204);
    String path = "/api/authorization/accounts/" + target + "/replace";
    String body = json.writeValueAsString(set(0, allow("ORGANIZATION_READ", b)));
    var noCsrf =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(403);
    token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    for (String invalid :
        List.of(
            "{\"expectedVersion\":0,\"rules\":[],\"assignments\":[],\"tenantId\":\"forged\"}",
            "{\"expectedVersion\":0.5,\"rules\":[],\"assignments\":[]}",
            "{}")) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Content-Type", "application/json")
              .header("X-CSRF-TOKEN", token)
              .POST(HttpRequest.BodyPublishers.ofString(invalid))
              .build();
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(400);
    }
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).get("version").asLong()).isEqualTo(1);
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(409);
    assertThat(get(client, "/api/authorization/accounts?search=" + foreignUser).body())
        .isEqualTo("[]");
  }
}
