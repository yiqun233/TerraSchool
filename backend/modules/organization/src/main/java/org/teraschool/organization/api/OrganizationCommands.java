package org.teraschool.organization.api;

import java.util.UUID;

public interface OrganizationCommands {
  OrganizationUnit createChild(CreateOrganizationUnit command);

  OrganizationUnit rename(UUID id, RenameOrganizationUnit command);

  java.util.List<CampusDistance> saveCampusDistances(UUID campusId, SaveCampusDistances command);
}
