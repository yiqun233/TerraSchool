package org.teraschool.finance.api;

import java.util.UUID;

public record FinanceSchoolOption(UUID id, String name, boolean canLock) {}
