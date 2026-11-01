package org.teraschool.finance.api;

import java.util.UUID;

public interface PeriodCommands {
  PeriodView lock(UUID schoolId, LockPeriod command);

  PeriodView unlock(UUID schoolId, LockPeriod command);
}
