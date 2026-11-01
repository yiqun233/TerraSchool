package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

/** 当前账号可用的课程产品能力；{@code maintainable} 标记该学校是否可写。 */
public record ProductCapabilities(
    boolean canRead, boolean canMaintain, boolean canPublish, List<ProductSchoolOption> schools) {
  public ProductCapabilities {
    schools = List.copyOf(schools);
  }

  public record ProductSchoolOption(UUID id, String name, boolean maintainable) {}
}
