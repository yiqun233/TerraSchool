package org.teraschool.organization.api;

import java.util.List;
import java.util.UUID;

/** 覆盖式保存某校区到同校其他校区的通勤分钟数；分钟数 0 表示删除该条。 */
public record SaveCampusDistances(UUID requestId, List<DistanceLine> items) {
  public record DistanceLine(UUID campusId, Integer minutes) {}

  public SaveCampusDistances {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
