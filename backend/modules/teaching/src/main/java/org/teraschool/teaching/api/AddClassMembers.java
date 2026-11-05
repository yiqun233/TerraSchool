package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/** 将一条有效报名原子加入同校多个课程班级；服务端拒绝重复目标。 */
public record AddClassMembers(UUID enrollmentId, List<UUID> classIds) {
  public AddClassMembers {
    classIds = classIds == null ? List.of() : List.copyOf(classIds);
  }
}
