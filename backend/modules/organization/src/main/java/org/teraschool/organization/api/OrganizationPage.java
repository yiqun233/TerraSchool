package org.teraschool.organization.api;

import java.util.List;

public record OrganizationPage(List<OrganizationUnit> items, int page, int size, long total) {}
