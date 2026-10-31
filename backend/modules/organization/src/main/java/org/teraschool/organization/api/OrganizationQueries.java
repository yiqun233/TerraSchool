package org.teraschool.organization.api;

public interface OrganizationQueries {
  OrganizationPage list(int page, int size);

  java.util.List<OrganizationUnit> schoolOptions(String search);
}
