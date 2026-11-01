package org.teraschool.approval.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 业务动作的审批闸门。
 *
 * <p>按「学校 + 动作」读取策略：未配置策略或金额低于免批阈值时直接通过；否则要求存在一张已批准、 动作/报名/金额完全匹配且未被使用过的审批单，并在同一事务内标记为已消耗。
 */
public interface ApprovalGate {
  void consume(String action, UUID schoolId, UUID enrollmentId, BigDecimal amount, UUID approvalId);
}
