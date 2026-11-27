package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

/**
 * 设置产品包含的课程（覆盖式）。
 *
 * <p>第一门课程成为产品主课程；每门课的课时之和应与已发布价格版本的课时一致。
 */
public record SetProductCurricula(
    UUID requestId, Long expectedVersion, List<CurriculumLine> items) {
  public SetProductCurricula {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /** 一门课程及其课时。 */
  public record CurriculumLine(UUID curriculumId, String hours) {}
}
