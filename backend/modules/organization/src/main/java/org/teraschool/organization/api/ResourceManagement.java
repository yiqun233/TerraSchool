package org.teraschool.organization.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 员工、教师和教室分别授权；教师可独立存在，也可显式关联员工。 */
public interface ResourceManagement {
  enum Kind {
    EMPLOYEE,
    TEACHER,
    ROOM
  }

  record ResourceView(
      UUID id,
      UUID schoolId,
      String schoolName,
      Kind kind,
      String name,
      UUID campusId,
      UUID departmentId,
      UUID employeeId,
      Integer capacity,
      boolean active,
      long version) {}

  record ResourcePage(List<ResourceView> items, int page, int size, long total) {}

  record ResourceCapabilities(Set<UUID> readableSchoolIds, Set<UUID> editableSchoolIds) {}

  record ResourceOption(UUID id, String name) {}

  record CreateResource(
      UUID requestId,
      UUID schoolId,
      String name,
      UUID campusId,
      UUID departmentId,
      UUID employeeId,
      Integer capacity) {}

  record UpdateResource(
      Long expectedVersion,
      String name,
      UUID campusId,
      UUID departmentId,
      UUID employeeId,
      Integer capacity,
      Boolean active) {}

  ResourceCapabilities capabilities(Kind kind);

  ResourcePage list(Kind kind, int page, int size);

  List<ResourceOption> options(Kind kind, String type, UUID schoolId, String search);

  ResourceView create(Kind kind, CreateResource input);

  ResourceView update(Kind kind, UUID id, UpdateResource input);
}
