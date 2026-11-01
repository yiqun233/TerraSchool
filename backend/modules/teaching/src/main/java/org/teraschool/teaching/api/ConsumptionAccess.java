package org.teraschool.teaching.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 他域读取课次消费汇总的最小契约（退课时需要判断可退课时）。
 *
 * <p>不做功能授权；调用方在自己的用例里完成权限检查，租户隔离由 RLS 承担。
 */
public interface ConsumptionAccess {
  record Consumed(UUID enrollmentId, BigDecimal hours, BigDecimal amount) {}

  Consumed consumed(UUID enrollmentId);
}
