package org.teraschool.archive.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

  @Override
  public Set<String> existingNumbers(UUID schoolId, Collection<String> studentNumbers) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (studentNumbers == null || studentNumbers.isEmpty()) return Set.of();
    var numbers = new ArrayList<>(studentNumbers);
    var found = new HashSet<String>();
    for (int offset = 0; offset < numbers.size(); offset += 500) {
      var part = numbers.subList(offset, Math.min(offset + 500, numbers.size()));
      var args = new ArrayList<Object>();
      args.add(schoolId);
      args.addAll(part);
      var marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
      for (var row :
          sql.fetch(
              "select student_no from archive.student where school_id=? and student_no in ("
                  + marks
                  + ")",
              args.toArray())) found.add(row.get("student_no", String.class));
    }
    return Set.copyOf(found);
  }
}
