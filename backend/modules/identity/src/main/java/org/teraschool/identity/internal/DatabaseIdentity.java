package org.teraschool.identity.internal;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.platform.api.TenantTransactions;

/** 认证基础设施只读独立账号表；会话不缓存权威租户状态、权限或范围。 */
@Service
public class DatabaseIdentity implements UserDetailsService, CurrentActor {
  private final JdbcTemplate jdbc;
  private final org.jooq.DSLContext sql;
  private final TenantTransactions tx;

  public DatabaseIdentity(
      @Qualifier("identityDataSource") DataSource source,
      org.jooq.DSLContext sql,
      TenantTransactions tx) {
    jdbc = new JdbcTemplate(source);
    this.sql = sql;
    this.tx = tx;
  }

  @Override
  public UserDetails loadUserByUsername(String login) {
    var users =
        jdbc.query(
            "select a.id,a.password_hash,a.credential_version from identity.account a join identity.tenant t on t.id=a.tenant_id where a.login=? and a.active and t.active",
            (r, n) ->
                User.withUsername(r.getString(1) + "~" + r.getLong(3))
                    .password(r.getString(2))
                    .authorities("AUTHENTICATED")
                    .build(),
            login);
    if (users.size() != 1) throw new UsernameNotFoundException("账号或凭据无效");
    return users.getFirst();
  }

  private record Snapshot(
      UUID account,
      UUID tenant,
      java.util.List<PermissionRules.Entry> rules,
      boolean tenantAdmin,
      boolean passwordChangeRequired,
      java.util.Set<UUID> tenantSchoolIds) {}

  /** 租户管理员要覆盖“全部学校（含以后新建的）”，每次取权限时现查，不缓存学校快照。 */
  private java.util.Set<UUID> tenantSchoolIds(UUID tenant) {
    return tx.execute(
        tenant,
        () ->
            new java.util.LinkedHashSet<>(
                sql.fetch(
                        "select id from organization.unit where tenant_id=? and kind='SCHOOL' order by id",
                        tenant)
                    .map(r -> r.get("id", UUID.class))));
  }

  private java.util.Set<UUID> tenantSchoolIdsInTransaction(UUID tenant) {
    return new java.util.LinkedHashSet<>(
        sql.fetch(
                "select id from organization.unit where tenant_id=? and kind='SCHOOL' order by id",
                tenant)
            .map(r -> r.get("id", UUID.class)));
  }

  private Snapshot snapshot() {
    var principal = SessionPrincipal.current();
    UUID account = principal.account();
    // 一条语句在同一快照加载可用状态与全部规则，不拼接不同读取时点的allow/deny。
    var rows =
        jdbc.query(
            PermissionRules.CURRENT,
            (r, n) ->
                new Object[] {
                  r.getObject(2, UUID.class),
                  r.getLong(6),
                  new PermissionRules.Entry(
                      r.getString(3), r.getObject(4, UUID.class), r.getString(5)),
                  r.getBoolean(7),
                  r.getBoolean(8)
                },
            account);
    if (rows.isEmpty()) throw new AccessDeniedException("账号或机构不可用");
    if (!Long.valueOf(principal.version()).equals(rows.getFirst()[1]))
      throw new AccessDeniedException("会话已撤销");
    var tenant = (UUID) rows.getFirst()[0];
    var tenantAdmin = (Boolean) rows.getFirst()[3];
    return new Snapshot(
        account,
        tenant,
        rows.stream().map(r -> (PermissionRules.Entry) r[2]).toList(),
        tenantAdmin,
        (Boolean) rows.getFirst()[4],
        tenantAdmin ? tenantSchoolIds(tenant) : java.util.Set.of());
  }

  @Override
  public CurrentPermissions permissions() {
    var value = snapshot();
    return PermissionRules.calculate(
        value.account(),
        value.tenant(),
        true,
        value.rules(),
        value.tenantAdmin(),
        value.passwordChangeRequired(),
        value.tenantSchoolIds());
  }

  @Override
  public java.util.List<ActionGrant> actionGrants() {
    var value = snapshot();
    return PermissionRules.grants(value.rules(), value.tenantAdmin(), value.tenantSchoolIds());
  }

  @Override
  public SessionContext sessionContext() {
    var value = permissions();
    var rows =
        jdbc.query(
            "select t.name,a.login from identity.account a join identity.tenant t on t.id=a.tenant_id where a.id=?",
            (r, n) -> new String[] {r.getString(1), r.getString(2)},
            value.accountId());
    if (rows.isEmpty()) throw new AccessDeniedException("账号或机构不可用");
    var login = rows.getFirst()[1];
    return new SessionContext(
        value.accountId(),
        value.tenantId(),
        rows.getFirst()[0],
        login,
        login,
        value.tenantAdmin(),
        value.passwordChangeRequired());
  }

  @Override
  public Actor requireResource(String kind, boolean maintenance) {
    var value = snapshot();
    return PermissionRules.resource(
        value.account(),
        value.tenant(),
        value.rules(),
        kind,
        maintenance,
        value.tenantAdmin(),
        value.tenantSchoolIds());
  }

  @Override
  public Actor requireResourceInTransaction(String kind, boolean maintenance) {
    var value = transactionSnapshot();
    return PermissionRules.resource(
        value.account(),
        value.tenant(),
        value.rules(),
        kind,
        maintenance,
        value.tenantAdmin(),
        value.tenantSchoolIds());
  }

  @Override
  public ResourceScope requireResourceScope(String kind) {
    var value = snapshot();
    return PermissionRules.scope(
        value.account(),
        value.tenant(),
        value.rules(),
        kind,
        value.tenantAdmin(),
        value.tenantSchoolIds());
  }

  @Override
  public Actor requireAction(String action) {
    var value = snapshot();
    return PermissionRules.action(
        value.account(),
        value.tenant(),
        value.rules(),
        action,
        value.tenantAdmin(),
        value.tenantSchoolIds());
  }

  @Override
  public Actor requireActionInTransaction(String action) {
    var value = transactionSnapshot();
    return PermissionRules.action(
        value.account(),
        value.tenant(),
        value.rules(),
        action,
        value.tenantAdmin(),
        value.tenantSchoolIds());
  }

  @Override
  public ResourceScope requireResourceScopeInTransaction(String kind) {
    var value = transactionSnapshot();
    return PermissionRules.scope(
        value.account(),
        value.tenant(),
        value.rules(),
        kind,
        value.tenantAdmin(),
        value.tenantSchoolIds());
  }

  private Snapshot transactionSnapshot() {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("资源权限复核要求业务事务");
    var principal = SessionPrincipal.current();
    var rows = sql.fetch(PermissionRules.CURRENT, principal.account());
    if (rows.isEmpty()) throw new AccessDeniedException("账号或机构不可用");
    if (!Long.valueOf(principal.version())
        .equals(rows.getFirst().get("credential_version", Long.class)))
      throw new AccessDeniedException("会话已撤销");
    var tenant = rows.getFirst().get("tenant_id", UUID.class);
    var tenantAdmin = rows.getFirst().get("tenant_admin", Boolean.class);
    return new Snapshot(
        principal.account(),
        tenant,
        rows.map(
            r ->
                new PermissionRules.Entry(
                    r.get("action", String.class),
                    r.get("school_id", UUID.class),
                    r.get("effect", String.class))),
        tenantAdmin,
        rows.getFirst().get("password_change_required", Boolean.class),
        tenantAdmin ? tenantSchoolIdsInTransaction(tenant) : java.util.Set.of());
  }

  @Override
  public Actor requireIdentityInTransaction() {
    var value = transactionSnapshot();
    return new Actor(value.account(), value.tenant(), java.util.Set.of());
  }

  @Override
  public Actor requireOrganizationMaintainerInTransaction() {
    var value = transactionSnapshot();
    var p =
        PermissionRules.calculate(
            value.account(),
            value.tenant(),
            true,
            value.rules(),
            value.tenantAdmin(),
            value.passwordChangeRequired(),
            value.tenantSchoolIds());
    if (!p.canReadOrganizations()) throw new AccessDeniedException("无组织查询权限");
    return new Actor(p.accountId(), p.tenantId(), p.maintainableSchoolIds());
  }

  @Override
  public Actor requireOrganizationReader() {
    var permission = permissions();
    if (!permission.canReadOrganizations()) throw new AccessDeniedException("无组织查询权限");
    return new Actor(permission.accountId(), permission.tenantId(), permission.readableSchoolIds());
  }

  @Override
  public Actor requireOrganizationMaintainer() {
    var permission = permissions();
    if (!permission.canReadOrganizations()) throw new AccessDeniedException("无组织查询权限");
    return new Actor(
        permission.accountId(), permission.tenantId(), permission.maintainableSchoolIds());
  }

  @Override
  public java.util.Optional<AuditSubject> auditSubject() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
      return java.util.Optional.empty();
    UUID id;
    try {
      id = SessionPrincipal.current().account();
    } catch (AccessDeniedException invalid) {
      return java.util.Optional.empty();
    }
    // 停用/撤权后的尝试仍需归属审计，但不能借此绕过requireOrganizationMaintainer。
    return jdbc
        .query(
            "select id,tenant_id from identity.account where id=?",
            (r, n) -> new AuditSubject(r.getObject(1, UUID.class), r.getObject(2, UUID.class)),
            id)
        .stream()
        .findFirst();
  }
}
