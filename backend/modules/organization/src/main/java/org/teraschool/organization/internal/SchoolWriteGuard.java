package org.teraschool.organization.internal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** 必须在授权机构锁内调用，保证停用与业务写入线性化。 */
@Component
public class SchoolWriteGuard {
  private final DSLContext sql;

  public SchoolWriteGuard(DSLContext sql) {
    this.sql = sql;
  }

  public void requireActive(UUID id) {
    if (sql.fetchOne("select id from organization.unit where id=? and kind='SCHOOL' and active", id)
        == null) throw new AccessDeniedException("学校已停用或不可访问");
  }

  public void lock(UUID tenant) {
    if (!"read committed".equals(sql.fetchOne("show transaction_isolation").get(0, String.class)))
      throw new IllegalStateException("学校写入要求READ COMMITTED事务");
    sql.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))", "authorization:" + tenant);
  }
}
