package org.teraschool.organization.api;

import java.util.List;
import java.util.UUID;

/** 学校级业务字典目录；系统内置字典暂不由此接口修改。 */
public interface DataDictionaries {
  record Item(
      UUID id,
      UUID schoolId,
      String dictionaryCode,
      String itemCode,
      String label,
      int sortOrder,
      boolean active,
      long version) {}

  record CreateItem(UUID requestId, String itemCode, String label, Integer sortOrder) {}

  record UpdateItem(Long expectedVersion, String label, Integer sortOrder, Boolean active) {}

  List<Item> list(UUID schoolId, String dictionaryCode, boolean includeInactive);

  Item create(UUID schoolId, String dictionaryCode, CreateItem command);

  Item update(UUID id, UpdateItem command);
}
