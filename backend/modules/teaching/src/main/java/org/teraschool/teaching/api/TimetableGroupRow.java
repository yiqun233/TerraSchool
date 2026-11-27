package org.teraschool.teaching.api;

import java.util.List;

/**
 * 按教师或学员聚合的一行课表。
 *
 * <p>key 是教师/学员 id；未排教师的课次归到 key=unassigned 的一行。
 */
public record TimetableGroupRow(
    String key, String label, String sublabel, List<TimetableCellView> cells) {
  public TimetableGroupRow {
    cells = cells == null ? List.of() : List.copyOf(cells);
  }
}
