package org.teraschool.finance.api;

import java.util.UUID;

/** 财务期间：自然月，状态 OPEN/LOCKED；锁定后该月资金与消费事实禁止写入。 */
public record PeriodView(
    UUID schoolId,
    String period,
    String status,
    String lockedAt,
    String lockedBy,
    String reason,
    long version) {}
