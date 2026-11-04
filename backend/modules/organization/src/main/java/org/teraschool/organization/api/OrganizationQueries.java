package org.teraschool.organization.api;

public interface OrganizationQueries {
  record EmployeeAssignmentOption(java.util.UUID accountId, String name) {}

  OrganizationPage list(int page, int size);

  java.util.List<OrganizationUnit> schoolOptions(String search);

  java.util.List<EmployeeAssignmentOption> customerAssignmentOptions(java.util.UUID schoolId);

  boolean isActiveCustomerAssignee(java.util.UUID schoolId, java.util.UUID accountId);
}
