package org.teraschool.admissions.api;

import java.util.UUID;

public interface CustomerFollowupCommands {
  record FollowupCreate(UUID requestId, String content, String occurredOn, String nextContactOn) {}

  CustomerFollowupQueries.Followup create(UUID customerId, FollowupCreate command);
}
