package org.teraschool.finance.api;

import java.util.List;
import java.util.UUID;

public interface PeriodQueries {
  FinanceCapabilities capabilities();

  /** 返回该学校指定年份（YYYY）的期间，未登记过的月份按 OPEN 返回。 */
  List<PeriodView> periods(UUID schoolId, String year);
}
