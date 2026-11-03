package org.teraschool.enrollment.api;

import java.util.List;
import java.util.UUID;

public record EnrollmentCapabilities(
    boolean canRead,
    boolean canMaintain,
    boolean canDiscount,
    boolean canApprove,
    List<EnrollmentSchoolOption> schools) {
  public EnrollmentCapabilities {
    schools = List.copyOf(schools);
  }

  public record EnrollmentSchoolOption(UUID id, String name, boolean maintainable) {}
}
