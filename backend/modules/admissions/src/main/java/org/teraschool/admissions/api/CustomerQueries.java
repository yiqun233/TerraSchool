package org.teraschool.admissions.api;

import java.util.List;
import java.util.UUID;

public interface CustomerQueries {
  record CustomerSchoolOption(
      UUID id,
      String name,
      boolean maintainable,
      boolean phoneReadable,
      boolean poolReadable,
      boolean poolManageable,
      boolean assignmentManageable) {}

  record CustomerAssignee(UUID accountId, String name) {}

  record CustomerCapabilities(List<CustomerSchoolOption> schools) {}

  record CustomerPage(List<Customer> items, int page, int size, boolean hasMore) {}

  record MergedCustomer(UUID sourceId, String name, long version, String mergedAt) {}

  CustomerCapabilities capabilities();

  CustomerRecyclePolicy recyclePolicy(UUID schoolId);

  CustomerPage list(UUID schoolId, int page, int size);

  CustomerPage pool(UUID schoolId, int page, int size);

  List<MergedCustomer> mergedInto(UUID primaryId);

  List<CustomerAssignee> assignableEmployees(UUID schoolId);
}
