package org.teraschool.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.teraschool.platform.api.RequestTrace;

/**
 * 平台运营账号：独立账号表、独立会话、独立审计，与租户账号没有任何交集。
 *
 * <p>会话只保存 token 的 SHA-256；库里拿到散列也不能直接登录。平台账号的动作权限来自 {@code
 * platform.operator_permission}，与租户权限目录是两套取值。
 */
@Component
class PlatformIdentity {
  static final String COOKIE = "TS_PLATFORM_SESSION";

  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final Duration sessionTtl;
  private final boolean secureCookie;
  private final SecureRandom random = new SecureRandom();

  PlatformIdentity(
      @Qualifier("platformDataSource") DataSource source,
      PasswordEncoder encoder,
      Environment env) {
    this.db = new JdbcTemplate(source);
    this.encoder = encoder;
    this.sessionTtl =
        Duration.ofMinutes(
            env.getProperty("teraschool.platform.session-ttl-minutes", Long.class, 480L));
    this.secureCookie =
        env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true);
  }

  record Operator(UUID id, String login, String displayName, Set<String> actions) {}

  /** 校验账号口令。失败一律返回空，调用方统一按“认证失败”处理， 不向客户端区分“账号不存在”和“口令错误”，避免枚举平台账号。 */
  Optional<Operator> authenticate(String login, String password) {
    if (login == null || password == null || login.isBlank()) return Optional.empty();
    var rows =
        db.query(
            "select id,login,display_name,password_hash from platform.operator where login=? and active",
            (rs, index) ->
                new Object[] {
                  rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)
                },
            login.strip());
    if (rows.isEmpty()) return Optional.empty();
    var row = rows.get(0);
    if (!encoder.matches(password, (String) row[3])) return Optional.empty();
    var id = (UUID) row[0];
    return Optional.of(new Operator(id, (String) row[1], (String) row[2], actions(id)));
  }

  /** 签发会话：返回给客户端的原始 token，库里只留散列。 */
  String open(UUID operatorId) {
    var bytes = new byte[32];
    random.nextBytes(bytes);
    var raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
        "insert into platform.operator_session(operator_id, token_hash, expires_at)"
            + " values (?,?, now() + make_interval(mins => ?))",
        operatorId,
        hash(raw),
        (int) sessionTtl.toMinutes());
    return raw;
  }

  Optional<Operator> resolve(String raw) {
    if (raw == null || raw.isBlank()) return Optional.empty();
    var rows =
        db.query(
            "select o.id,o.login,o.display_name from platform.operator_session s"
                + " join platform.operator o on o.id=s.operator_id"
                + " where s.token_hash=? and s.expires_at > now() and o.active",
            (rs, index) ->
                new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)},
            hash(raw));
    if (rows.isEmpty()) return Optional.empty();
    db.update(
        "update platform.operator_session set last_seen_at=now() where token_hash=?", hash(raw));
    var row = rows.get(0);
    var id = (UUID) row[0];
    return Optional.of(new Operator(id, (String) row[1], (String) row[2], actions(id)));
  }

  void close(String raw) {
    if (raw != null && !raw.isBlank())
      db.update("delete from platform.operator_session where token_hash=?", hash(raw));
  }

  /** 平台审计：独立表，写不进去就是写不进去，静默失败会掩盖越权操作。 */
  void audit(UUID operatorId, String action, String changedFields) {
    db.update(
        "insert into platform.audit_event(operator_id, action, changed_fields, trace_id)"
            + " values (?,?,?,?)",
        operatorId,
        action,
        changedFields == null ? "" : changedFields,
        RequestTrace.id());
  }

  Duration sessionTtl() {
    return sessionTtl;
  }

  boolean secureCookie() {
    return secureCookie;
  }

  private Set<String> actions(UUID operatorId) {
    return new TreeSet<>(
        db.queryForList(
            "select action from platform.operator_permission where operator_id=? and effect='ALLOW'",
            String.class,
            operatorId));
  }

  private static String hash(String raw) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      var text = new StringBuilder(64);
      for (byte value : digest) text.append(String.format("%02x", value));
      return text.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
