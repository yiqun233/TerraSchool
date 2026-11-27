package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

/** 设置产品可售校区；空列表表示全校区可售。 */
public record SetProductCampuses(UUID requestId, Long expectedVersion, List<UUID> campusIds) {
  public SetProductCampuses {
    campusIds = campusIds == null ? List.of() : List.copyOf(campusIds);
  }
}
