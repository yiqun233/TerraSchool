package org.teraschool.archive.api;

import java.util.UUID;

public interface StudentQueries {
  StudentPage list(int page, int size, String search);

  StudentProfile detail(UUID id);

  StudentCapabilities capabilities();
}
