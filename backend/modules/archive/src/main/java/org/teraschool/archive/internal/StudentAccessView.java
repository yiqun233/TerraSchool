package org.teraschool.archive.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentAccess;

/** 学员读取的对外适配；租户隔离由连接上的强制 RLS 承担，功能授权由调用方负责。 */
@Service
public class StudentAccessView implements StudentAccess {
  private final DSLContext sql;

  public StudentAccessView(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public StudentRef require(UUID studentId) {
    if (studentId == null) throw new IllegalArgumentException("需要学员");
    var row =
        sql.fetchOne(
            "select id,school_id,student_no,name from archive.student where id=?", studentId);
    if (row == null) throw new AccessDeniedException("学员档案不存在或不可访问");
    return new StudentRef(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("student_no", String.class),
        row.get("name", String.class));
  }

  @Override
  public Map<UUID, String> names(Collection<UUID> studentIds) {
    if (studentIds == null || studentIds.isEmpty()) return Map.of();
    var result = new HashMap<UUID, String>();
    var marks = String.join(",", java.util.Collections.nCopies(studentIds.size(), "?"));
    for (var row :
        sql.fetch(
            "select id,name from archive.student where id in (" + marks + ")",
            studentIds.toArray()))
      result.put(row.get("id", UUID.class), row.get("name", String.class));
    return result;
  }
}
