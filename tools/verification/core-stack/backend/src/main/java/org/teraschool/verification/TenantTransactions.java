package org.teraschool.verification;

import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 同一实际 Spring 事务连接绑定租户，事务结束清除；禁止在用例内切换租户。 */
@Component
class TenantTransactions {
    private final DSLContext sql;
    private final TransactionTemplate transactions;

    TenantTransactions(DSLContext sql, @Qualifier("appTransactionManager") PlatformTransactionManager manager) {
        this.sql = sql;
        this.transactions = new TransactionTemplate(manager);
    }

    <T> T inTenant(UUID tenant, Supplier<T> work) {
        if (tenant == null) throw new IllegalArgumentException("租户不能为空");
        return transactions.execute(status -> {
            String bound = sql.fetchOne("select current_setting('app.tenant_id', true)").get(0, String.class);
            if (bound != null && !bound.isBlank() && !bound.equals(tenant.toString()))
                throw new IllegalStateException("同一事务不得切换租户");
            sql.fetch("select set_config('app.tenant_id', ?, true)", tenant.toString());
            return work.get();
        });
    }
}
