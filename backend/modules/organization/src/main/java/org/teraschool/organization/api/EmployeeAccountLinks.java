package org.teraschool.organization.api;

import java.util.UUID;

public interface EmployeeAccountLinks {
  record AccountLink(
      UUID employeeId, UUID schoolId, UUID accountId, String accountLogin, long version) {}

  record ChangeAccountLink(Long expectedVersion, UUID accountId) {}

  AccountLink getLink(UUID employeeId);

  AccountLink changeLink(UUID employeeId, ChangeAccountLink input);
}
