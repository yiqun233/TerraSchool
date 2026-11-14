package org.teraschool.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 租户开通：一次操作产出「租户 + 租户管理员」，不建学校（DEC-024）。
 *
 * <p>真正的写入放在 {@code platform.provision_tenant} 函数里，由迁移角色拥有： 平台角色对 {@code identity.account}
 * 没有写权限，业务表更是一张都没有。这样“平台能开通” 的权限范围恰好等于这一个函数，而不是把账号表交出去。
 */
@Component
class TenantProvisioning {
  /** 首版密码策略：≥12 位、不得与登录名相同、不得为纯数字或纯字母。 */
  static final int MIN_PASSWORD = 12;

  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  TenantProvisioning(@Qualifier("platformDataSource") DataSource source, PasswordEncoder encoder) {
    this.db = new JdbcTemplate(source);
    this.encoder = encoder;
  }

  record Request(UUID requestId, String name, String adminLogin, String adminPassword) {}

  record Provisioned(UUID tenantId, UUID accountId) {}

  /** 参数不合法直接拒绝，不进入数据库。 */
  static void validate(Request request) {
    if (request.requestId() == null) throw new IllegalArgumentException("需要请求身份");
    var name = request.name() == null ? "" : request.name().strip();
    if (name.isEmpty()
        || name.length() > 100
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("机构名称需在 1～100 字符且不含控制字符");
    var login = request.adminLogin() == null ? "" : request.adminLogin().strip();
    if (login.length() < 3 || login.length() > 60)
      throw new IllegalArgumentException("管理员登录名需在 3～60 字符");
    var password = request.adminPassword() == null ? "" : request.adminPassword();
    if (password.length() < MIN_PASSWORD) throw new IllegalArgumentException("初始密码至少 12 位");
    if (password.equalsIgnoreCase(login)) throw new IllegalArgumentException("初始密码不能与登录名相同");
    var digits = password.chars().allMatch(Character::isDigit);
    var letters = password.chars().allMatch(Character::isLetter);
    if (digits || letters) throw new IllegalArgumentException("初始密码不能是纯数字或纯字母");
  }

  Provisioned provision(PlatformIdentity.Operator operator, Request request) {
    validate(request);
    var name = request.name().strip();
    var login = request.adminLogin().strip();
    var fingerprint = fingerprint(operator.id(), name, login);
    try {
      var rows =
          db.query(
              "select tenant_id, account_id from platform.provision_tenant(?,?,?,?,?,?,?)",
              (rs, index) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
              operator.id(),
              request.requestId(),
              fingerprint,
              name,
              login,
              encoder.encode(request.adminPassword()),
              name);
      var row = rows.getFirst();
      return new Provisioned(row[0], row[1]);
    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
      // identity.account.login 全局唯一：登录名被占用时给出稳定业务码。
      throw new ProvisioningConflict("ADMIN_LOGIN_TAKEN");
    } catch (org.springframework.dao.DataAccessException error) {
      var message = error.getMostSpecificCause().getMessage();
      if (message != null && message.contains("IDEMPOTENCY_MISMATCH"))
        throw new ProvisioningConflict("IDEMPOTENCY_MISMATCH");
      if (message != null && message.contains("TENANT_NAME_TAKEN"))
        throw new ProvisioningConflict("TENANT_NAME_TAKEN");
      throw error;
    }
  }

  /**
   * 停用或恢复租户。返回是否真的改变了状态；状态未变时按幂等处理，不写第二条审计。
   *
   * <p>写权限不在平台角色上，只在 {@code platform.set_tenant_active} 函数里。
   */
  boolean setActive(PlatformIdentity.Operator operator, UUID tenantId, boolean active) {
    try {
      return Boolean.TRUE.equals(
          db.queryForObject(
              "select platform.set_tenant_active(?,?,?)",
              Boolean.class,
              operator.id(),
              tenantId,
              active));
    } catch (org.springframework.dao.DataAccessException error) {
      var message = error.getMostSpecificCause().getMessage();
      if (message != null && message.contains("TENANT_NOT_FOUND")) throw new TenantMissing();
      throw error;
    }
  }

  /** 目标租户不存在：由接口层转成 404。 */
  static class TenantMissing extends RuntimeException {}

  /** 单个租户的基本信息；不存在返回空。 */
  Optional<Map<String, Object>> tenant(UUID tenantId) {
    var rows =
        db.queryForList(
            "select id, name, active, created_at from identity.tenant where id=?", tenantId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  /** 租户详情：学校数、账号数、管理员数；只有数字，没有业务明细。 */
  Map<String, Object> summary(UUID tenantId) {
    return db.queryForMap("select * from platform.tenant_summary(?)", tenantId);
  }

  /**
   * 记录一次开通失败。
   *
   * <p>函数体内失败会整体回滚，失败原因只能由调用方在事务外补记；同一 requestId 重试时 函数会清掉这条失败记录再继续，所以这里用 upsert 而不是普通插入。
   */
  void recordFailure(PlatformIdentity.Operator operator, Request request, String code) {
    var name = request.name() == null ? "" : request.name().strip();
    var login = request.adminLogin() == null ? "" : request.adminLogin().strip();
    // 机构名与登录名一并落库：失败行没有 tenant_id，开通记录页要靠这两列说明“谁失败了”。
    db.update(
        "insert into platform.provisioning_request"
            + "(operator_id,request_id,fingerprint,status,error_code,"
            + " tenant_name,admin_login,finished_at)"
            + " values (?,?,?,'FAILED',?,?,?,now())"
            + " on conflict (operator_id,request_id) do update"
            + " set status='FAILED', error_code=excluded.error_code, finished_at=now(),"
            + " tenant_id=null, account_id=null,"
            + " tenant_name=excluded.tenant_name, admin_login=excluded.admin_login",
        operator.id(),
        request.requestId(),
        fingerprint(operator.id(), name, login),
        code,
        name.isEmpty() ? null : name,
        login.isEmpty() ? null : login);
  }

  /** 列出某租户的租户管理员：登录名与状态。 */
  List<Map<String, Object>> administrators(UUID tenantId) {
    return db.queryForList("select login, active from platform.tenant_administrators(?)", tenantId);
  }

  /**
   * 重置租户管理员密码：置回强制改密并递增凭据版本，既有会话立即失效。
   *
   * <p>返回 false 表示该租户不存在这个管理员；调用方据此给 404，而不是假装成功。
   */
  boolean resetAdministratorPassword(
      PlatformIdentity.Operator operator, UUID tenantId, String login, String password) {
    if (login == null || login.isBlank()) throw new IllegalArgumentException("需要管理员登录名");
    if (password.length() < MIN_PASSWORD) throw new IllegalArgumentException("初始密码至少 12 位");
    var digits = password.chars().allMatch(Character::isDigit);
    var letters = password.chars().allMatch(Character::isLetter);
    if (digits || letters) throw new IllegalArgumentException("初始密码不能是纯数字或纯字母");
    var updated =
        db.queryForObject(
            "select platform.reset_tenant_admin_password(?,?,?,?)",
            Boolean.class,
            operator.id(),
            tenantId,
            login.strip(),
            encoder.encode(password));
    return Boolean.TRUE.equals(updated);
  }

  /** 平台列表的状态与关键字过滤；只作用于开通信息，不触碰业务数据。 */
  private record TenantFilter(String where, List<Object> args) {}

  private TenantFilter filter(String status, String keyword) {
    var cleanStatus = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
    if (!cleanStatus.isEmpty() && !Set.of("ACTIVE", "SUSPENDED").contains(cleanStatus))
      throw new IllegalArgumentException("租户状态筛选无效");
    var cleanKeyword = keyword == null ? "" : keyword.strip();
    if (cleanKeyword.length() > 100) throw new IllegalArgumentException("关键字过长");
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if ("ACTIVE".equals(cleanStatus)) where.append(" and t.active");
    else if ("SUSPENDED".equals(cleanStatus)) where.append(" and not t.active");
    if (!cleanKeyword.isEmpty()) {
      where.append(" and t.name ilike ?");
      args.add("%" + cleanKeyword + "%");
    }
    return new TenantFilter(where.toString(), args);
  }

  /** 租户统计：总数、正常、停用、本月开通；按同一平台可见范围计数。 */
  record TenantCounts(long total, long active, long suspended, long thisMonth) {}

  TenantCounts counts() {
    return db.queryForObject(
        "select count(*) as total,"
            + " count(*) filter (where active) as active,"
            + " count(*) filter (where not active) as suspended,"
            + " count(*) filter (where created_at >= date_trunc('month', current_date)) as this_month"
            + " from identity.tenant",
        (rs, index) ->
            new TenantCounts(
                rs.getLong("total"),
                rs.getLong("active"),
                rs.getLong("suspended"),
                rs.getLong("this_month")));
  }

  /** 按筛选条件计数：页脚显示总数，不靠“本页是否满 20 条”猜下一页。 */
  long countTenants(String status, String keyword) {
    var value = filter(status, keyword);
    return db.queryForObject(
        "select count(*) from identity.tenant t" + value.where(),
        Long.class,
        value.args().toArray());
  }

  /**
   * 平台只读租户基本信息：名称、状态、开通时间与管理员登录名。
   *
   * <p>登录名来自受控函数 {@code platform.tenant_administrators}，平台角色仍读不到账号表；
   * 多个管理员用顿号连接，停用的标注出来，便于运营核对交付给客户的账号。
   */
  List<Map<String, Object>> tenants(int page, int size, String status, String keyword) {
    var value = filter(status, keyword);
    var args = new ArrayList<>(value.args());
    args.add(size);
    args.add(page * size);
    return db.queryForList(
        "select t.id, t.name, t.active, t.created_at,"
            + " (select string_agg("
            + "   case when a.active then a.login else a.login || '（停用）' end,"
            + "   '、' order by a.login)"
            + "  from platform.tenant_administrators(t.id) a) as admin_logins"
            + " from identity.tenant t"
            + value.where()
            + " order by t.created_at desc, t.id limit ? offset ?",
        args.toArray());
  }

  static String fingerprint(UUID operator, String name, String login) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest((operator + "\n" + name + "\n" + login).getBytes(StandardCharsets.UTF_8));
      var text = new StringBuilder(64);
      for (byte value : digest) text.append(String.format("%02x", value));
      return text.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 开通冲突：携带稳定业务码，由接口层转成 RFC 9457。 */
  static class ProvisioningConflict extends RuntimeException {
    private final String code;

    ProvisioningConflict(String code) {
      super(code);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
