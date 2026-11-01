package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.*;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.AccountLifecycle.*;
import org.teraschool.identity.api.AuthorizationManagement.*;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountLifecycleTest {
  @Autowired AccountLifecycle accounts;
  @Autowired AuthorizationManagement grants;
  @Autowired CurrentActor actors;
  @Autowired TenantTransactions tx;
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

  HttpResponse<String> login(HttpClient c, UUID account, String password) throws Exception {
    var token = json.readTree(get(c, "/api/session/csrf").body()).get("token").asText();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN", token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=" + account + "&password=" + password))
            .build();
    return c.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void createHasNoPermissionsAndPasswordNeverAppearsInAudit() throws Exception {
    String password = "different-secret-" + UUID.randomUUID();
    String login = "new-" + UUID.randomUUID();
    var created = accounts.createAccount(new CreateAccount(login, password));
    assertThat(created.active()).isTrue();
    assertThat(created.version()).isZero();
    assertThat(grants.get(created.id()).effective().canReadOrganizations()).isFalse();
    assertThatThrownBy(() -> accounts.createAccount(new CreateAccount(login, password)))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("ACCOUNT_LOGIN_UNAVAILABLE");
    assertThatThrownBy(() -> accounts.createAccount(new CreateAccount("short-" + login, "123")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            f.count(
                "select count(*) from audit.account_lifecycle_event where account_id=? and row_to_json(account_lifecycle_event)::text like '%different-secret%'",
                created.id()))
        .isZero();
    assertThat(json.writeValueAsString(created)).doesNotContain(password, "password_hash");
  }

  @Test
  void lastEffectiveAdminAndDelegationAreProtected() throws Exception {
    var root = accounts.getAccount(f.root);
    assertThatThrownBy(
            () -> accounts.changeStatus(f.root, new ChangeAccountStatus(root.version(), false)))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("LAST_AUTHORIZATION_ADMINISTRATOR");
    assertThat(accounts.getAccount(f.root).active()).isTrue();
    f.grant(f.target, "ORGANIZATION_READ", f.b, "ALLOW");
    f.as(f.delegate);
    assertThatThrownBy(() -> accounts.getAccount(f.target))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                accounts.resetPassword(
                    f.target, new ResetAccountPassword(0L, "very-long-password")))
        .isInstanceOf(AccessDeniedException.class);
    f.as(f.root);
    assertThatThrownBy(() -> accounts.getAccount(f.foreignUser))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void disableEnableCannotResurrectSessionsEvenWithoutInterveningRequest() throws Exception {
    var old = client();
    assertThat(login(old, f.target, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    var before = accounts.getAccount(f.target);
    var disabled =
        accounts.changeStatus(f.target, new ChangeAccountStatus(before.version(), false));
    var enabled =
        accounts.changeStatus(f.target, new ChangeAccountStatus(disabled.version(), true));
    assertThat(enabled.version()).isEqualTo(before.version() + 2);
    assertThat(get(old, "/api/session/permissions").statusCode()).isEqualTo(401);
    var fresh = client();
    assertThat(login(fresh, f.target, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    assertThat(get(fresh, "/api/organization/units").statusCode()).isEqualTo(200);
  }

  @Test
  void resetAndExplicitRevokeInvalidateOtherSessions() throws Exception {
    var first = client();
    var second = client();
    assertThat(login(first, f.target, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    assertThat(login(second, f.target, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    String next = "new-secret-" + UUID.randomUUID();
    var changed = accounts.resetPassword(f.target, new ResetAccountPassword(0L, next));
    assertThat(get(first, "/api/session/permissions").statusCode()).isEqualTo(401);
    assertThat(get(second, "/api/session/permissions").statusCode()).isEqualTo(401);
    assertThat(login(client(), f.target, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(401);
    var current = client();
    assertThat(login(current, f.target, next).statusCode()).isEqualTo(204);
    accounts.revokeSessions(f.target, new RevokeAccountSessions(changed.version()));
    assertThat(get(current, "/api/session/permissions").statusCode()).isEqualTo(401);
    assertThatThrownBy(() -> accounts.revokeSessions(f.target, new RevokeAccountSessions(0L)))
        .isInstanceOf(AuthorizationConflict.class)
        .hasMessage("ACCOUNT_VERSION_CONFLICT");
  }

  @Test
  void databaseTriggerAdvancesVersionWhenAnotherWriteChangesStatus() throws Exception {
    var old = client();
    assertThat(login(old, f.target, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    update("update identity.account set active=false where id=?", f.target);
    update("update identity.account set active=true where id=?", f.target);
    assertThat(get(old, "/api/session/permissions").statusCode()).isEqualTo(401);
    assertThat(accounts.getAccount(f.target).version()).isEqualTo(2);
    assertThatThrownBy(
            () -> update("update identity.account set credential_version=0 where id=?", f.target))
        .isInstanceOf(java.sql.SQLException.class);
  }

  @Test
  void lifecycleAndAuditRollbackTogether() throws Exception {
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      accounts.changeStatus(f.target, new ChangeAccountStatus(0L, false));
                      throw new IllegalStateException("outer");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(accounts.getAccount(f.target).active()).isTrue();
    assertThat(accounts.getAccount(f.target).version()).isZero();
    assertThat(
            f.count(
                "select count(*) from audit.account_lifecycle_event where account_id=?", f.target))
        .isZero();
    assertThat(
            f.count(
                "select count(*) from audit.operation_failure where resource_id=? and action='ACCOUNT_STATUS' and code='TRANSACTION_ROLLED_BACK'",
                f.target))
        .isEqualTo(1);
  }

  @Test
  void queuedRequestRechecksRevokedManagerSessionUnderLock() throws Exception {
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
                        accounts.revokeSessions(f.delegate, new RevokeAccountSessions(0L));
                        held.countDown();
                        try {
                          release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return true;
                      });
                } finally {
                  f.clear();
                }
              });
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      var waiting =
          pool.submit(
              () -> {
                f.as(f.delegate);
                try {
                  accounts.changeStatus(f.target, new ChangeAccountStatus(0L, false));
                  return false;
                } catch (AccessDeniedException denied) {
                  return true;
                } finally {
                  f.clear();
                }
              });
      boolean locked = false;
      for (int n = 0; n < 100; n++) {
        if (f.count(
                "select count(*) from pg_stat_activity where usename='ts_app' and wait_event='advisory' and query like '%pg_advisory_xact_lock%'")
            > 0) {
          locked = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(locked).isTrue();
      release.countDown();
      assertThat(holder.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(waiting.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(accounts.getAccount(f.target).active()).isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void httpAccountCreationRequiresCsrfAndDoesNotReturnPassword() throws Exception {
    var client = client();
    assertThat(login(client, f.root, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    String password = "http-secret-" + UUID.randomUUID(), login = "http-" + UUID.randomUUID();
    String body = json.writeValueAsString(new CreateAccount(login, password));
    var uri = URI.create("http://127.0.0.1:" + port + "/api/authorization/account-lifecycle");
    var unsafe =
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(client.send(unsafe, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(403);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var safe =
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = client.send(safe, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.body()).doesNotContain(password, "password_hash");
    assertThat(client.send(safe, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
  }
}
