package org.teraschool.organization.api;

public interface OrganizationQueries {
  record EmployeeAssignmentOption(java.util.UUID accountId, String name) {}

  OrganizationPage list(int page, int size);

  java.util.List<OrganizationUnit> schoolOptions(String search);

  java.util.List<EmployeeAssignmentOption> customerAssignmentOptions(java.util.UUID schoolId);

  boolean isActiveCustomerAssignee(java.util.UUID schoolId, java.util.UUID accountId);

  java.util.List<EmployeeAssignmentOption> studentAssignmentOptions(java.util.UUID schoolId);

  boolean isActiveStudentAssignee(java.util.UUID schoolId, java.util.UUID accountId);

  /**
   * 当前账号在本租户的员工姓名；没有绑定员工时返回 null。
   *
   * <p>只用于会话顶栏呈现，不作为权限或人员身份判断；同账号多条在职记录时取姓名排序第一条。
   */
  String employeeDisplayName(java.util.UUID accountId);
}
