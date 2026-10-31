package org.teraschool.identity.internal;

import java.util.HashSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;

/** 认证基础设施只读独立账号表；会话不缓存权威租户状态、权限或范围。 */
@Service
public class DatabaseIdentity implements UserDetailsService, CurrentActor {
  private final JdbcTemplate jdbc;

  public DatabaseIdentity(@Qualifier("identityDataSource") DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  @Override
  public UserDetails loadUserByUsername(String login) {
    var users =
        jdbc.query(
            "select a.id,a.password_hash from identity.account a join identity.tenant t on t.id=a.tenant_id where a.login=? and a.active and t.active",
            (r, n) ->
                User.withUsername(r.getString(1))
                    .password(r.getString(2))
                    .authorities("AUTHENTICATED")
                    .build(),
            login);
    if (users.size() != 1) throw new UsernameNotFoundException("账号或凭据无效");
    return users.getFirst();
  }

  @Override
  public Actor requireOrganizationReader() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("需要登录");
    UUID id;
    try {
      id = UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      throw new AccessDeniedException("需要有效身份");
    }
    var tenants =
        jdbc.query(
            "select a.tenant_id from identity.account a join identity.tenant t on t.id=a.tenant_id where a.id=? and a.active and t.active and a.organization_read",
            (r, n) -> r.getObject(1, UUID.class),
            id);
    if (tenants.size() != 1) throw new AccessDeniedException("无组织查询权限");
    var schools =
        jdbc.query(
            "select school_id from identity.school_scope where account_id=?",
            (r, n) -> r.getObject(1, UUID.class),
            id);
    return new Actor(id, tenants.getFirst(), new HashSet<>(schools));
  }

  @Override
  public Actor requireOrganizationMaintainer() {
    var reader = requireOrganizationReader();
    // 维护范围与读取范围取交集；同学校任一 DENY 压过 ALLOW。
    var schools =
        jdbc.query(
            "select p.school_id from identity.organization_maintenance_permission p "
                + "where p.account_id=? and p.tenant_id=? and p.effect='ALLOW' "
                + "and not exists (select 1 from identity.organization_maintenance_permission d "
                + "where d.account_id=p.account_id and d.school_id=p.school_id and d.effect='DENY')",
            (r, n) -> r.getObject(1, UUID.class),
            reader.accountId(),
            reader.tenantId());
    var effective = new HashSet<>(schools);
    effective.retainAll(reader.schools());
    return new Actor(reader.accountId(), reader.tenantId(), effective);
  }
}
