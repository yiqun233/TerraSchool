package org.teraschool.identity.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.FieldAccess;

/** 读取字段授权；DENY 优先于 ALLOW，与功能动作的语义保持一致。 */
@Service
public class FieldGrantService implements FieldAccess {
  private final DSLContext sql;

  public FieldGrantService(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public Set<String> readableFields(UUID accountId, UUID schoolId) {
    var allowed = new HashSet<>(grants(accountId, schoolId).allowed());
    allowed.removeAll(grants(accountId, schoolId).denied());
    return Set.copyOf(allowed);
  }

  @Override
  public Set<String> deniedFields(UUID accountId, UUID schoolId) {
    return grants(accountId, schoolId).denied();
  }

  private Grants grants(UUID accountId, UUID schoolId) {
    if (accountId == null || schoolId == null) return new Grants(Set.of(), Set.of());
    var allowed = new HashSet<String>();
    var denied = new HashSet<String>();
    for (var row :
        sql.fetch(
            "select field_key,effect from identity.field_grant where account_id=? and school_id=?",
            accountId,
            schoolId)) {
      var key = row.get("field_key", String.class);
      if ("DENY".equals(row.get("effect", String.class))) denied.add(key);
      else allowed.add(key);
    }
    return new Grants(Set.copyOf(allowed), Set.copyOf(denied));
  }

  private record Grants(Set<String> allowed, Set<String> denied) {}
}
