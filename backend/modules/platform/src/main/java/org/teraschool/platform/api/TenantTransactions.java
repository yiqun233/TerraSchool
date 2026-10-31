package org.teraschool.platform.api;

import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 事务局部租户只绑定当前实际连接，禁止嵌套调用切换租户。 */
@Component
public class TenantTransactions {
  private final DSLContext sql;
  private final TransactionTemplate tx;

  public TenantTransactions(DSLContext sql, PlatformTransactionManager manager) {
    this.sql = sql;
    tx = new TransactionTemplate(manager);
  }

  public <T> T execute(UUID tenant, Supplier<T> work) {
    if (tenant == null) throw new IllegalArgumentException("缺少租户");
    return tx.execute(
        status -> {
          String current =
              sql.fetchOne("select current_setting('app.tenant_id',true)").get(0, String.class);
          if (current != null && !current.isBlank() && !current.equals(tenant.toString()))
            throw new IllegalStateException("事务内禁止切换租户");
          sql.fetch("select set_config('app.tenant_id',?,true)", tenant.toString());
          return work.get();
        });
  }
}
