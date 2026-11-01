package org.teraschool.platform.api;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 本批组织名称变更的追加审计；必须加入调用方现有业务事务。 */
@Component
public class BusinessAudit {
  private final DSLContext sql;

  public BusinessAudit(DSLContext sql) {
    this.sql = sql;
  }

  public void append(
      UUID tenant,
      UUID actor,
      String action,
      UUID resource,
      UUID school,
      String before,
      String after) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("审计必须在业务事务内");
    sql.execute(
        "insert into audit.business_event(tenant_id,actor_id,action,resource_id,school_id,old_name,new_name,trace_id) values (?,?,?,?,?,?,?,?)",
        tenant,
        actor,
        action,
        resource,
        school,
        before,
        after,
        RequestTrace.id());
  }
}
