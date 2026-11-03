package org.teraschool.admissions.api;

import java.util.List;
import java.util.UUID;

public interface CustomerFollowupQueries {
  record Followup(
      UUID id,
      UUID customerId,
      String content,
      String occurredOn,
      String nextContactOn,
      String recordedAt) {}

  record FollowupPage(List<Followup> items, int page, int size, boolean hasMore) {}

  FollowupPage list(UUID customerId, int page, int size);
}
