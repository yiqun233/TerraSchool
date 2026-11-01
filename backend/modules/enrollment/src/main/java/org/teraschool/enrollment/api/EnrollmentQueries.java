package org.teraschool.enrollment.api;

import java.util.UUID;

public interface EnrollmentQueries {
  EnrollmentCapabilities capabilities();

  EnrollmentPage list(UUID schoolId, String status, int page, int size);

  EnrollmentView detail(UUID id);
}
