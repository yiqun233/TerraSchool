package org.teraschool.product.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.product.api.PriceAccess;
import org.teraschool.product.api.PriceSnapshot;

/** 价格版本读取的对外适配；租户隔离由强制 RLS 承担，授权由调用方负责。 */
@Service
public class PriceAccessView implements PriceAccess {
  private final DSLContext sql;

  public PriceAccessView(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public PriceSnapshot require(UUID priceVersionId) {
    if (priceVersionId == null) throw new IllegalArgumentException("需要价格版本");
    var row =
        sql.fetchOne(
            "select pv.id,pv.product_id,p.school_id,p.curriculum_id,c.name as curriculum_name,"
                + "p.name as product_name,pv.price,pv.hours,pv.service_fee,pv.settlement_mode,"
                + "pv.study_days,pv.status,pv.effective_from,pv.effective_to"
                + " from product.price_version pv"
                + " join product.product p on p.id=pv.product_id"
                + " join product.curriculum c on c.id=p.curriculum_id"
                + " where pv.id=?",
            priceVersionId);
    if (row == null) throw new AccessDeniedException("价格版本不存在或不可访问");
    return new PriceSnapshot(
        row.get("id", UUID.class),
        row.get("product_id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("curriculum_id", UUID.class),
        row.get("curriculum_name", String.class),
        row.get("product_name", String.class),
        money(row.get("price", BigDecimal.class)),
        money(row.get("hours", BigDecimal.class)),
        money(row.get("service_fee", BigDecimal.class)),
        row.get("settlement_mode", Integer.class),
        row.get("study_days", Integer.class),
        row.get("status", String.class),
        date(row.get("effective_from", LocalDate.class)),
        date(row.get("effective_to", LocalDate.class)));
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private String date(LocalDate value) {
    return value == null ? null : value.toString();
  }
}
