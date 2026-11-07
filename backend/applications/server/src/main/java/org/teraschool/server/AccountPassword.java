package org.teraschool.server;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/**
 * 本人改密：首次登录强制改密与主动改密共用同一条路径。
 *
 * <p>改密会递增 `credential_version`，因此**当前会话随即失效**，需要重新登录。 这是有意的：其它设备上的旧会话也必须一起失效，否则“改了密码”并不等于把别人踢下线。
 */
@Component
class AccountPassword {
  static final int MIN_LENGTH = 12;

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final PasswordEncoder encoder;

  /** 口令散列的读取走身份连接：业务角色按安全不变量不允许读它。 */
  private final org.springframework.jdbc.core.JdbcTemplate secrets;

  AccountPassword(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      PasswordEncoder encoder,
      @org.springframework.beans.factory.annotation.Qualifier("identityDataSource")
          DataSource identitySource) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.encoder = encoder;
    this.secrets = new org.springframework.jdbc.core.JdbcTemplate(identitySource);
  }

  void change(String currentPassword, String newPassword) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      changeInTrace(currentPassword, newPassword);
    }
  }

  private void changeInTrace(String currentPassword, String newPassword) {
    var identity = actors.permissions();
    tx.execute(
        identity.tenantId(),
        () -> {
          var actor = actors.requireIdentityInTransaction();
          var row =
              secrets.queryForList(
                  "select login,password_hash,credential_version from identity.account"
                      + " where id=? and active",
                  actor.accountId());
          if (row.isEmpty()) throw new AccessDeniedException("账号不可用");
          var record = row.getFirst();
          if (currentPassword == null
              || !encoder.matches(currentPassword, (String) record.get("password_hash")))
            throw new IllegalArgumentException("当前密码不正确");
          var login = (String) record.get("login");
          validate(newPassword, login, currentPassword);
          var previous = ((Number) record.get("credential_version")).longValue();
          var next = previous + 1;
          sql.execute(
              "update identity.account set password_hash=?, password_change_required=false,"
                  + " credential_version=? where id=?",
              encoder.encode(newPassword),
              next,
              actor.accountId());
          sql.execute(
              "insert into audit.account_lifecycle_event(tenant_id,actor_id,account_id,action,"
                  + "previous_version,next_version,active,trace_id)"
                  + " values (?,?,?,'PASSWORD_CHANGED',?,?,true,?)",
              actor.tenantId(),
              actor.accountId(),
              actor.accountId(),
              previous,
              next,
              RequestTrace.id());
          return null;
        });
  }

  /** 首版口令策略：长度、不得与登录名相同、不得与旧口令相同、不得为纯数字或纯字母。 */
  static void validate(String password, String login, String current) {
    if (password == null || password.length() < MIN_LENGTH)
      throw new IllegalArgumentException("新密码至少 " + MIN_LENGTH + " 位");
    if (password.length() > 72) throw new IllegalArgumentException("新密码过长");
    if (login != null && password.equalsIgnoreCase(login))
      throw new IllegalArgumentException("新密码不能与登录名相同");
    if (password.equals(current)) throw new IllegalArgumentException("新密码不能与当前密码相同");
    var digits = password.chars().allMatch(Character::isDigit);
    var letters = password.chars().allMatch(Character::isLetter);
    if (digits || letters) throw new IllegalArgumentException("新密码不能是纯数字或纯字母");
  }
}
