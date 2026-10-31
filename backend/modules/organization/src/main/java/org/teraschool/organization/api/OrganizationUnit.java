package org.teraschool.organization.api;

import java.util.UUID;

public record OrganizationUnit(UUID id, UUID schoolId, String kind, String name, long version) {}
