package org.teraschool.finance.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 财务期间闸门。
 *
 * <p>资金与消费事实写库前按业务日期检查期间：该月已锁定时拒绝写入，避免已结算月份被回写。 未登记的期间视为开放。
 */
public interface PeriodGate {
  void requireOpen(UUID schoolId, LocalDate businessDate);

  /** 批量读取锁定月份；调用方须在租户事务中自行校验对应学校的期间读取权限。 */
  Set<YearMonth> lockedMonths(UUID schoolId, Collection<YearMonth> months);
}
