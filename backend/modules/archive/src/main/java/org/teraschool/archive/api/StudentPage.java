package org.teraschool.archive.api;

import java.util.List;

public record StudentPage(List<StudentSummary> items, int page, int size, long total) {
  public StudentPage {
    items = List.copyOf(items);
  }
}
