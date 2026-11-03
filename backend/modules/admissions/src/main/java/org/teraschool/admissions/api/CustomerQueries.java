package org.teraschool.admissions.api;

import java.util.List;
import java.util.UUID;

public interface CustomerQueries {
  record CustomerSchoolOption(UUID id, String name, boolean maintainable, boolean phoneReadable) {}

  record CustomerCapabilities(List<CustomerSchoolOption> schools) {}

  record CustomerPage(List<Customer> items, int page, int size, boolean hasMore) {}

  CustomerCapabilities capabilities();

  CustomerPage list(UUID schoolId, int page, int size);
}
