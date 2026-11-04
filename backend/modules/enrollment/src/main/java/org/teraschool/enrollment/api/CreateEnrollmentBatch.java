package org.teraschool.enrollment.api;

import java.util.List;

/** 一名学员在一次事务中登记多个不同产品；每个子单保留独立请求幂等键。 */
public record CreateEnrollmentBatch(List<CreateEnrollment> items) {
  public CreateEnrollmentBatch {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
