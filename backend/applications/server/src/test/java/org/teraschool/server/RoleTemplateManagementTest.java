package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.*;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.AuthorizationManagement.*;
import org.teraschool.identity.api.RoleTemplateManagement.*;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoleTemplateManagementTest {
  @Autowired RoleTemplateManagement roles;
  @Autowired AuthorizationManagement accounts;
  @Autowired CurrentActor actors;
  @Autowired TenantTransactions tx;
  @Autowired DSLContext sql;
  @LocalServerPort int port;
  AuthorizationManagementTest f;
  final JsonMapper json = JsonMapper.builder().build();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeEach
  void seed() throws Exception {
    f = new AuthorizationManagementTest();
    f.seed();
  }

  @AfterEach
  void clear() {
    f.clear();
  }

  RoleAction allow(String action) {
    return new RoleAction(action, "ALLOW");
  }

  RoleTemplate create(String code, RoleAction... actions) {
    return roles.createRole(new CreateRole(UUID.randomUUID(), code, "测试角色", List.of(actions)));
  }

  AccountAuthorization assign(UUID account, RoleTemplate role, UUID... schools) {
    var old = accounts.get(account);
    return accounts.replace(
        account,
        new Replacement(
            old.version(),
            old.rules(),
            Arrays.stream(schools)
                .map(s -> new Assignment(role.id(), s, roles.getRole(role.id()).version()))
                .toList()));
  }

  /** 目录里的每个动作都必须能通过公开授权接口保存，否则该权限永远授不出去。 */
  @Test
  void everyCatalogActionCanBeSavedInARole() {
    var role =
        create(
            "ALL_CATALOG",
            PermissionCatalog.ACTIONS.stream()
                .map(action -> allow(action.code()))
                .toArray(RoleAction[]::new));
    assertThat(role.actions())
        .extracting(RoleAction::action)
        .containsExactlyInAnyOrderElementsOf(PermissionCatalog.CODES);
  }

  @Test
  void createIsIdempotentAndCodeIsTenantUnique() {
    UUID request = UUID.randomUUID();
    var a =
        roles.createRole(
            new CreateRole(
                request, " test_role ", "  测试角色  ", List.of(allow("ORGANIZATION_READ"))));
    var b =
        roles.createRole(
            new CreateRole(request, "TEST_ROLE", "测试角色", List.of(allow("ORGANIZATION_READ"))));
    assertThat(b).isEqualTo(a);
    assertThat(a.code()).isEqualTo("TEST_ROLE");
    assertThat(a.version()).isZero();
    assertThat(roles.listRoles("TEST_ROLE", true))
        .extracting(RoleSummary::id)
        .containsExactly(a.id());
    assertThatThrownBy(
            () -> roles.createRole(new CreateRole(request, "TEST_ROLE", "不同名称", List.of())))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("ROLE_REQUEST_MISMATCH");
    assertThatThrownBy(() -> create("TEST_ROLE"))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("ROLE_CODE_EXISTS");
  }

  @Test
  void sharedDefinitionAndMembershipInvalidateBothKindsOfEditors() throws Exception {
    var original = create("SHARED", allow("ORGANIZATION_READ"));
    var beforeAccount = assign(f.target, original, f.a);
    assertThatThrownBy(
            () -> roles.replaceRole(original.id(), new RoleChange(0L, "旧影响范围", true, List.of())))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("ROLE_VERSION_CONFLICT");
    var current = roles.getRole(original.id());
    assertThat(current.assignedAccounts()).isEqualTo(1);
    assertThat(current.version()).isEqualTo(1);
    var changed =
        roles.replaceRole(
            current.id(),
            new RoleChange(
                current.version(),
                "已修改角色",
                true,
                List.of(allow("ORGANIZATION_READ"), allow("ORGANIZATION_MAINTAIN"))));
    assertThat(changed.code()).isEqualTo("SHARED");
    assertThat(accounts.get(f.target).effective().maintainableSchoolIds()).containsExactly(f.a);
    assertThat(accounts.get(f.target).version()).isEqualTo(beforeAccount.version() + 1);
    assertThatThrownBy(
            () ->
                accounts.replace(
                    f.target, new Replacement(beforeAccount.version(), List.of(), List.of())))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("AUTHORIZATION_VERSION_CONFLICT");
    assertThat(
            f.count(
                "select count(*) from audit.role_template_event where role_id=? and affected_accounts=1 and action='ROLE_TEMPLATE_REPLACE'",
                changed.id()))
        .isEqualTo(1);
  }

  @Test
  void creationReplayCannotBypassChangedDelegationBoundary() {
    f.as(f.delegate);
    var input =
        new CreateRole(
            UUID.randomUUID(), "ORIGINAL_CREATOR", "原创建者角色", List.of(allow("ORGANIZATION_READ")));
    var created = roles.createRole(input);
    f.as(f.root);
    assign(f.target, created, f.b);
    f.as(f.delegate);
    assertThatThrownBy(() -> roles.createRole(input)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void selectingAnUnassignedRoleBindsItsDefinitionVersion() {
    var role = create("SELECTED_READER", allow("ORGANIZATION_READ"));
    var target = accounts.get(f.target);
    var changed =
        roles.replaceRole(
            role.id(),
            new RoleChange(
                role.version(), role.name(), true, List.of(allow("AUTHORIZATION_MANAGE"))));
    assertThat(accounts.get(f.target).version()).isEqualTo(target.version());
    assertThatThrownBy(
            () ->
                accounts.replace(
                    f.target,
                    new Replacement(
                        target.version(),
                        target.rules(),
                        List.of(new Assignment(role.id(), f.a, role.version())))))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("ROLE_VERSION_CONFLICT");
    assertThat(accounts.get(f.target).effective().manageableSchoolIds()).isEmpty();
    assertThat(
            accounts
                .replace(
                    f.target,
                    new Replacement(
                        target.version(),
                        target.rules(),
                        List.of(new Assignment(role.id(), f.a, changed.version()))))
                .effective()
                .manageableSchoolIds())
        .containsExactly(f.a);
  }

  @Test
  void allAffectedAccountConfigurationMustFitDelegationEvenWhenMasked() throws Exception {
    var role = create("DELEGATED", allow("ORGANIZATION_READ"));
    assign(f.target, role, f.a);
    f.grant(f.target, "ORGANIZATION_READ", f.b, "ALLOW");
    f.grant(f.target, "ORGANIZATION_READ", null, "DENY");
    f.as(f.delegate);
    assertThat(roles.listRoles("", true)).extracting(RoleSummary::id).doesNotContain(role.id());
    assertThatThrownBy(() -> roles.getRole(role.id())).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> roles.replaceRole(role.id(), new RoleChange(1L, "无权改名", true, List.of())))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                roles.createRole(new CreateRole(UUID.randomUUID(), "DELEGATED", "测试角色", List.of())))
        .isInstanceOf(AuthorizationConflict.class);
    f.as(f.root);
    update(
        "delete from identity.account_permission where account_id=? and (school_id=? or school_id is null)",
        f.target,
        f.b);
    f.as(f.delegate);
    assertThat(
            roles
                .replaceRole(
                    role.id(),
                    new RoleChange(
                        1L,
                        "校内维护角色",
                        true,
                        List.of(allow("ORGANIZATION_READ"), allow("ORGANIZATION_MAINTAIN"))))
                .name())
        .isEqualTo("校内维护角色");
  }

  @Test
  void assignmentOutsideSchoolBlocksEditAndForeignOrNonManagerDenied() {
    var role = create("OUTSIDE", allow("ORGANIZATION_READ"));
    assign(f.target, role, f.b);
    f.as(f.delegate);
    assertThatThrownBy(() -> roles.getRole(role.id())).isInstanceOf(AccessDeniedException.class);
    f.as(f.foreignUser);
    assertThatThrownBy(() -> roles.getRole(role.id())).isInstanceOf(AccessDeniedException.class);
    f.as(f.target);
    assertThatThrownBy(() -> create("FORBIDDEN")).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void lastAdministratorRoleCannotBeDisabledEmptiedOrDenied() throws Exception {
    var role = create("ADMIN_ROLE", allow("AUTHORIZATION_MANAGE"));
    accounts.replace(
        f.root,
        new Replacement(
            0L,
            List.of(),
            List.of(
                new Assignment(role.id(), f.a, role.version()),
                new Assignment(role.id(), f.b, role.version()))));
    var before = roles.getRole(role.id());
    for (var change :
        List.of(
            new RoleChange(before.version(), "管理员", false, before.actions()),
            new RoleChange(before.version(), "管理员", true, List.of()),
            new RoleChange(
                before.version(),
                "管理员",
                true,
                List.of(
                    allow("AUTHORIZATION_MANAGE"),
                    new RoleAction("AUTHORIZATION_MANAGE", "DENY"))))) {
      assertThatThrownBy(() -> roles.replaceRole(role.id(), change))
          .isInstanceOf(AuthorizationConflict.class)
          .hasMessage("LAST_AUTHORIZATION_ADMINISTRATOR");
    }
    assertThat(roles.getRole(role.id()).version()).isEqualTo(before.version());
    assertThat(accounts.get(f.root).version()).isEqualTo(1);
    UUID backup = f.user(f.tenant);
    f.grant(backup, "AUTHORIZATION_MANAGE", f.a, "ALLOW");
    f.grant(backup, "AUTHORIZATION_MANAGE", f.b, "ALLOW");
    var disabled =
        roles.replaceRole(
            role.id(), new RoleChange(before.version(), "管理员", false, before.actions()));
    assertThat(disabled.active()).isFalse();
    assertThat(actors.permissions().manageableSchoolIds()).isEmpty();
    f.as(backup);
    assertThat(accounts.get(f.root).version()).isEqualTo(2);
  }

  @Test
  void disableAndEnableRetainAssignmentsAndReapplyDenyPriority() {
    var role = create("SWITCHED", allow("ORGANIZATION_READ"), allow("ORGANIZATION_MAINTAIN"));
    assign(f.target, role, f.a);
    var before = roles.getRole(role.id());
    var disabled =
        roles.replaceRole(
            role.id(), new RoleChange(before.version(), before.name(), false, before.actions()));
    assertThat(accounts.get(f.target).assignments()).hasSize(1);
    assertThat(accounts.get(f.target).effective().maintainableSchoolIds()).isEmpty();
    var enabled =
        roles.replaceRole(
            role.id(),
            new RoleChange(
                disabled.version(),
                before.name(),
                true,
                List.of(
                    allow("ORGANIZATION_READ"),
                    allow("ORGANIZATION_MAINTAIN"),
                    new RoleAction("ORGANIZATION_MAINTAIN", "DENY"))));
    assertThat(enabled.active()).isTrue();
    assertThat(accounts.get(f.target).effective().maintainableSchoolIds()).isEmpty();
    assertThat(accounts.get(f.target).effective().readableSchoolIds()).containsExactly(f.a);
  }

  @Test
  void concurrentTemplateChangesNeverMerge() throws Exception {
    var role = create("CONCURRENT");
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<Boolean>>();
      for (String action : List.of("ORGANIZATION_READ", "ORGANIZATION_MAINTAIN"))
        tasks.add(
            pool.submit(
                () -> {
                  f.as(f.root);
                  try {
                    start.await();
                    roles.replaceRole(
                        role.id(), new RoleChange(0L, "并发", true, List.of(allow(action))));
                    return true;
                  } catch (AuthorizationConflict conflict) {
                    return false;
                  } finally {
                    f.clear();
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var task : tasks) if (task.get(20, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      assertThat(roles.getRole(role.id()).actions()).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void queuedCreatorLosesAccessAfterRevocationCommits() throws Exception {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holder =
          pool.submit(
              () -> {
                f.as(f.root);
                try {
                  return tx.execute(
                      f.tenant,
                      () -> {
                        accounts.replace(f.delegate, new Replacement(0L, List.of(), List.of()));
                        held.countDown();
                        try {
                          release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                          throw new RuntimeException(error);
                        }
                        return true;
                      });
                } finally {
                  f.clear();
                }
              });
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      var queued =
          pool.submit(
              () -> {
                f.as(f.delegate);
                try {
                  create("QUEUED");
                  return false;
                } catch (AccessDeniedException denied) {
                  return true;
                } finally {
                  f.clear();
                }
              });
      boolean waiting = false;
      for (int n = 0; n < 100; n++) {
        if (f.count(
                "select count(*) from pg_stat_activity where usename='ts_app' and wait_event='advisory' and query like '%pg_advisory_xact_lock%'")
            > 0) {
          waiting = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(waiting).isTrue();
      release.countDown();
      assertThat(holder.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(queued.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(roles.listRoles("", true)).extracting(RoleSummary::code).doesNotContain("QUEUED");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void rollbackIncludesTemplateAccountVersionsAndAuditAndKeepsFailure() throws Exception {
    var role = create("ROLLBACK", allow("ORGANIZATION_READ"));
    assign(f.target, role, f.a);
    var before = roles.getRole(role.id());
    long accountVersion = accounts.get(f.target).version();
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      roles.replaceRole(
                          role.id(), new RoleChange(before.version(), "应回滚", false, List.of()));
                      throw new IllegalStateException("outer");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(roles.getRole(role.id())).isEqualTo(before);
    assertThat(accounts.get(f.target).version()).isEqualTo(accountVersion);
    assertThat(
            f.count(
                "select count(*) from audit.role_template_event where role_id=? and action='ROLE_TEMPLATE_REPLACE'",
                role.id()))
        .isZero();
    assertThat(
            f.count(
                "select count(*) from audit.operation_failure where resource_id=? and action='ROLE_TEMPLATE_REPLACE' and code='TRANSACTION_ROLLED_BACK'",
                role.id()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      create("ROLLBACK_CREATE");
                      throw new IllegalStateException("outer");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(roles.listRoles("", true))
        .extracting(RoleSummary::code)
        .doesNotContain("ROLLBACK_CREATE");
    assertThat(
            f.count(
                "select count(*) from audit.operation_failure where tenant_id=? and action='ROLE_TEMPLATE_CREATE' and code='TRANSACTION_ROLLED_BACK'",
                f.tenant))
        .isEqualTo(1);
  }

  @Test
  void validatesInputsAndKeepsCodeAndHistoryImmutableAtDatabaseBoundary() {
    assertThatThrownBy(() -> create("bad-code")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                create("DUPLICATE_ACTION", allow("ORGANIZATION_READ"), allow("ORGANIZATION_READ")))
        .isInstanceOf(IllegalArgumentException.class);
    var role = create("IMMUTABLE");
    assertThatThrownBy(() -> roles.replaceRole(role.id(), new RoleChange(0L, "x", null, List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () ->
                        sql.execute(
                            "update identity.permission_role set code='NEW_CODE' where id=?",
                            role.id())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () ->
                        sql.execute("delete from identity.permission_role where id=?", role.id())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> tx.execute(f.tenant, () -> sql.fetch("select * from audit.role_template_event")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () ->
                        sql.execute(
                            "insert into identity.permission_role(tenant_id,code,name) values (?,'CROSS_TENANT','越界')",
                            f.other)))
        .isInstanceOf(RuntimeException.class);
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpCreateReplayStrictInputAndDisabledListAreReal() throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var login =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN", token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=" + f.root + "&password=" + AuthorizationManagementTest.PASSWORD))
            .build();
    assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(204);
    String path = "/api/authorization/role-templates",
        body =
            json.writeValueAsString(
                new CreateRole(
                    UUID.randomUUID(), "HTTP_ROLE", "HTTP角色", List.of(allow("ORGANIZATION_READ"))));
    var raw =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(client.send(raw, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var request =
        HttpRequest.newBuilder(raw.uri())
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var created = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).body())
        .isEqualTo(created.body());
    UUID id = UUID.fromString(json.readTree(created.body()).get("id").asText());
    var change =
        HttpRequest.newBuilder(URI.create(raw.uri() + "/" + id + "/replace"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"expectedVersion\":0.5,\"name\":\"bad\",\"active\":false,\"actions\":[]}"))
            .build();
    assertThat(client.send(change, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(400);
    var unknown =
        HttpRequest.newBuilder(raw.uri())
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    body.substring(0, body.length() - 1) + ",\"tenantId\":\"forged\"}"))
            .build();
    assertThat(client.send(unknown, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(400);
    roles.replaceRole(id, new RoleChange(0L, "HTTP角色", false, List.of()));
    assertThat(get(client, path).body()).doesNotContain(id.toString());
    assertThat(get(client, path + "?includeInactive=true").body()).contains(id.toString());
  }
}
