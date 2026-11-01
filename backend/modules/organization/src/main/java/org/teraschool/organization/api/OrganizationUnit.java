package org.teraschool.organization.api;

import java.util.UUID;

public record OrganizationUnit(
    UUID id, UUID schoolId, String kind, String name, long version, boolean active) {
  public OrganizationUnit(UUID id, UUID schoolId, String kind, String name, long version) {
    this(id, schoolId, kind, name, version, true);
  }
}
