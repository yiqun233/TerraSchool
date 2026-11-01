package org.teraschool.enrollment.api;

import java.util.List;

public record EnrollmentPage(List<EnrollmentView> items, int page, int size, long total) {
  public EnrollmentPage {
    items = List.copyOf(items);
  }
}
