package org.teraschool.organization.api;

import java.util.UUID;

public record OrganizationUnit(
    UUID id,
    UUID schoolId,
    String kind,
    String name,
    long version,
    boolean active,
    String shortName,
    String address,
    String postalCode,
    String busRoutes) {
  public OrganizationUnit(UUID id, UUID schoolId, String kind, String name, long version) {
    this(id, schoolId, kind, name, version, true, null, null, null, null);
  }

  public OrganizationUnit(
      UUID id, UUID schoolId, String kind, String name, long version, boolean active) {
    this(id, schoolId, kind, name, version, active, null, null, null, null);
  }
}
