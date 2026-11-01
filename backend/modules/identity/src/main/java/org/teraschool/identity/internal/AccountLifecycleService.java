package org.teraschool.identity.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.AccountLifecycle.*;
import org.teraschool.platform.api.RequestTrace;

@Service
public class AccountLifecycleService {
  private final AccountAuthorizations authorization;
  private final DSLContext sql;
  private final PasswordEncoder passwords;

  public AccountLifecycleService(
      AccountAuthorizations authorization, DSLContext sql, PasswordEncoder passwords) {
    this.authorization = authorization;
    this.sql = sql;
    this.passwords = passwords;
  }

  private AccountView load(UUID id, CurrentPermissions actor) {
    var account = authorization.load(id);
    authorization.boundary(actor, account);
    long version =
        sql.fetchOne("select credential_version from identity.account where id=?", id)
            .get(0, Long.class);
    return new AccountView(id, account.login(), account.active(), version);
  }

  private String password(String value) {
    if (value == null
        || value.isBlank()
        || value.length() < 12
        || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException("密码至少12字符，UTF-8不超过72字节");
    return passwords.encode(value);
  }

  private void audit(
      CurrentPermissions actor, String action, AccountView before, AccountView after) {
    sql.execute(
        "insert into audit.account_lifecycle_event(tenant_id,actor_id,account_id,action,previous_version,next_version,active,trace_id) values (?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        after.id(),
        action,
        before == null ? null : before.version(),
        after.version(),
        after.active(),
        RequestTrace.id());
  }

  private void check(Long expected, AccountView before) {
    if (expected == null || expected < 0) throw new IllegalArgumentException("需要有效版本");
    if (before.version() != expected) throw new AuthorizationConflict("ACCOUNT_VERSION_CONFLICT");
  }

  public AccountView getAccount(UUID id) {
    return authorization.managed(actor -> load(id, actor));
  }

  public AccountView createAccount(CreateAccount input) {
    return authorization.managed(
        actor -> {
          if (input == null || input.login() == null)
            throw new IllegalArgumentException("需要登录名和密码");
          String login = input.login().strip();
          if (login.isEmpty()
              || login.length() > 150
              || login.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("登录名须为1到150字符且不含控制字符");
          String hash = password(input.password());
          UUID id;
          try {
            id =
                sql.fetchOne(
                        "insert into identity.account(tenant_id,login,password_hash,active) values (?,?,?,true) returning id",
                        actor.tenantId(),
                        login,
                        hash)
                    .get(0, UUID.class);
          } catch (DuplicateKeyException duplicate) {
            throw new AuthorizationConflict("ACCOUNT_LOGIN_UNAVAILABLE");
          }
          var result = load(id, actor);
          audit(actor, "ACCOUNT_CREATE", null, result);
          return result;
        });
  }

  public AccountView changeStatus(UUID id, ChangeAccountStatus input) {
    return authorization.managed(
        actor -> {
          if (input == null || input.active() == null) throw new IllegalArgumentException("需要账号状态");
          var before = load(id, actor);
          check(input.expectedVersion(), before);
          if (sql.execute(
                  "update identity.account set active=?,credential_version=credential_version+1 where id=? and credential_version=?",
                  input.active(),
                  id,
                  input.expectedVersion())
              != 1) throw new AuthorizationConflict("ACCOUNT_VERSION_CONFLICT");
          if (!authorization.hasFullAdministrator())
            throw new AuthorizationConflict("LAST_AUTHORIZATION_ADMINISTRATOR");
          var after = load(id, actor);
          audit(actor, "ACCOUNT_STATUS", before, after);
          return after;
        });
  }

  public AccountView resetPassword(UUID id, ResetAccountPassword input) {
    return authorization.managed(
        actor -> {
          if (input == null) throw new IllegalArgumentException("需要密码和版本");
          var before = load(id, actor);
          check(input.expectedVersion(), before);
          String hash = password(input.password());
          if (sql.execute(
                  "update identity.account set password_hash=?,credential_version=credential_version+1 where id=? and credential_version=?",
                  hash,
                  id,
                  input.expectedVersion())
              != 1) throw new AuthorizationConflict("ACCOUNT_VERSION_CONFLICT");
          var after = load(id, actor);
          audit(actor, "ACCOUNT_PASSWORD_RESET", before, after);
          return after;
        });
  }

  public AccountView revokeSessions(UUID id, RevokeAccountSessions input) {
    return authorization.managed(
        actor -> {
          if (input == null) throw new IllegalArgumentException("需要版本");
          var before = load(id, actor);
          check(input.expectedVersion(), before);
          if (sql.execute(
                  "update identity.account set credential_version=credential_version+1 where id=? and credential_version=?",
                  id,
                  input.expectedVersion())
              != 1) throw new AuthorizationConflict("ACCOUNT_VERSION_CONFLICT");
          var after = load(id, actor);
          audit(actor, "ACCOUNT_SESSIONS_REVOKE", before, after);
          return after;
        });
  }
}
