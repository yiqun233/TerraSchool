package org.teraschool.product.internal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.product.api.CurriculumAccess;

/** 课程读取的对外适配；租户隔离由强制 RLS 承担。 */
@Service
public class CurriculumAccessView implements CurriculumAccess {
  private final DSLContext sql;

  public CurriculumAccessView(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public CurriculumRef require(UUID curriculumId) {
    if (curriculumId == null) throw new IllegalArgumentException("需要课程");
    var row =
        sql.fetchOne(
            "select id,school_id,name,active from product.curriculum where id=?", curriculumId);
    if (row == null) throw new AccessDeniedException("课程不存在或不可访问");
    return new CurriculumRef(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("name", String.class),
        row.get("active", Boolean.class));
  }
}
