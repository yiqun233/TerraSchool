package org.teraschool.organization.api;

import java.util.UUID;

public interface OrganizationCommands {
  OrganizationUnit createChild(CreateOrganizationUnit command);

  OrganizationUnit rename(UUID id, RenameOrganizationUnit command);
}
