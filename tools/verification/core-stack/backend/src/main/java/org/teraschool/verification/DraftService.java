package org.teraschool.verification;

import static org.teraschool.verification.generated.tables.ProbeDraft.PROBE_DRAFT;
import static org.teraschool.verification.generated.tables.ProbeAudit.PROBE_AUDIT;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/** HTTP 和第二入口共用应用用例，鉴权和输入验证不能只放在 Controller。 */
@Service
class DraftService {
    private final DSLContext sql;
    private final TrustedActor actors;
    private final TenantTransactions transactions;
    private final Validator validator;

    DraftService(DSLContext sql, TrustedActor actors, TenantTransactions transactions, Validator validator) {
        this.sql = sql; this.actors = actors; this.transactions = transactions; this.validator = validator;
    }

    DraftView create(DraftCommand command) {
        var tenant = actors.tenant(true);
        var violations = validator.validate(command);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        var amount = new BigDecimal(command.amount());
        if (amount.signum() <= 0) throw new IllegalArgumentException("金额必须大于零");
        return transactions.inTenant(tenant, () -> {
            var row = sql.insertInto(PROBE_DRAFT).set(PROBE_DRAFT.TENANT_ID, tenant)
                .set(PROBE_DRAFT.AMOUNT, amount).set(PROBE_DRAFT.BUSINESS_DATE, command.businessDate())
                .set(PROBE_DRAFT.HAPPENED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(PROBE_DRAFT.NOTE, command.note()).returning().fetchOne();
            sql.insertInto(PROBE_AUDIT).set(PROBE_AUDIT.TENANT_ID, tenant)
                .set(PROBE_AUDIT.ACTOR, org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName())
                .set(PROBE_AUDIT.ACTION, "CREATE_PROBE_DRAFT").set(PROBE_AUDIT.OBJECT_ID, row.getId()).execute();
            return new DraftView(row.getId(), row.getAmount().toPlainString(), row.getBusinessDate(), row.getHappenedAt().toInstant(), row.getNote());
        });
    }

    List<DraftView> list() {
        var tenant = actors.tenant(false);
        return transactions.inTenant(tenant, () -> sql.selectFrom(PROBE_DRAFT).orderBy(PROBE_DRAFT.ID).limit(50)
            .fetch(r -> new DraftView(r.getId(), r.getAmount().toPlainString(), r.getBusinessDate(), r.getHappenedAt().toInstant(), r.getNote())));
    }
}
