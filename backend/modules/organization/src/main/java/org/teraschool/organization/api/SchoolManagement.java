package org.teraschool.organization.api;

import java.util.UUID;

public interface SchoolManagement {
  record CreateSchool(UUID requestId, String name) {}

  record SchoolView(UUID id, String name, boolean active, long version) {}

  record UpdateSchool(Long expectedVersion, String name, Boolean active) {}

  boolean canCreate();

  OrganizationUnit createSchool(CreateSchool input);

  SchoolView getSchool(UUID id);

  SchoolView updateSchool(UUID id, UpdateSchool input);
}
