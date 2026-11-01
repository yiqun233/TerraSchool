package org.teraschool.finance.api;

import java.util.UUID;

/** 锁定或解锁某个月份：目标学校、原因与（解锁时）期望版本。 */
public record LockPeriod(UUID schoolId, String period, String reason, Long expectedVersion) {}
