package org.teraschool.teaching.api;

import java.util.List;

/** 一次原子提交的多班排课请求。 */
public record ScheduleBatchRequest(List<ScheduleBatchItem> items) {
  public ScheduleBatchRequest {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
