package org.teraschool.admissions.api;

import java.util.UUID;

public interface ChannelCommands {
  record ChannelCreate(UUID requestId, UUID schoolId, String name) {}

  record ChannelUpdate(String name, Boolean active, Long expectedVersion) {}

  Channel create(ChannelCreate command);

  Channel update(UUID id, ChannelUpdate command);
}
