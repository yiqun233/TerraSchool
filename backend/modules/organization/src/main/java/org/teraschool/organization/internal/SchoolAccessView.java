package org.teraschool.organization.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.organization.api.SchoolAccess;

/** 学校读取与机构锁的对外适配；租户隔离由连接上的强制 RLS 承担。 */
@Service
public class SchoolAccessView implements SchoolAccess {
  private final DSLContext sql;
  private final SchoolWriteGuard guard;

  public SchoolAccessView(DSLContext sql, SchoolWriteGuard guard) {
    this.sql = sql;
    this.guard = guard;
  }

  @Override
  public void lockTenant(UUID tenantId) {
    guard.lock(tenantId);
  }

  @Override
  public School require(UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var row =
        sql.fetchOne(
            "select id,name,active from organization.unit where id=? and kind='SCHOOL'", schoolId);
    if (row == null) throw new AccessDeniedException("学校不可访问");
    return new School(
        row.get("id", UUID.class), row.get("name", String.class), row.get("active", Boolean.class));
  }

  @Override
  public void requireCampus(UUID schoolId, UUID campusId) {
    if (campusId == null) throw new IllegalArgumentException("需要校区");
    // 校区必须直属该学校且启用；跨学校的校区关联会让范围校验失效。
    var row =
        sql.fetchOne(
            "select id from organization.unit where id=? and school_id=? and kind='CAMPUS' and active",
            campusId,
            schoolId);
    if (row == null) throw new AccessDeniedException("校区不属于该学校或已停用");
  }

  @Override
  public Map<UUID, String> names(Collection<UUID> schoolIds) {
    if (schoolIds == null || schoolIds.isEmpty()) return Map.of();
    var result = new HashMap<UUID, String>();
    var marks = String.join(",", java.util.Collections.nCopies(schoolIds.size(), "?"));
    for (var row :
        sql.fetch(
            "select id,name from organization.unit where kind='SCHOOL' and id in (" + marks + ")",
            schoolIds.toArray()))
      result.put(row.get("id", UUID.class), row.get("name", String.class));
    return result;
  }
}
