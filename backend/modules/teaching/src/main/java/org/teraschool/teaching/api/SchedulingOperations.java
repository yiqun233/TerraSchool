package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/** 排课及资源占用的公开应用契约。 */
public interface SchedulingOperations {
  SchedulePreview preview(UUID classId, ScheduleRule rule);

  ScheduleResult confirm(UUID classId, ScheduleRule rule);

  ResourceBlockView createBlock(ResourceBlock command);

  List<ResourceBlockView> blocks(UUID schoolId, String from, String to);

  SchedulePolicyView policyOf(UUID schoolId);

  SchedulePolicyView savePolicy(UUID schoolId, SchedulePolicy command);

  boolean deleteBlock(UUID id);
}
