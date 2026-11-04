package org.teraschool.enrollment.api;

import java.util.List;

/** 原子多产品报名结果，顺序与提交项一致。 */
public record EnrollmentBatch(List<EnrollmentView> items) {
  public EnrollmentBatch {
    items = List.copyOf(items);
  }
}
