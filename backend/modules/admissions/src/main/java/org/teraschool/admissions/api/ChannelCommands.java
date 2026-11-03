package org.teraschool.admissions.api;

import java.util.UUID;

public interface ChannelCommands {
  record ChannelCreate(UUID requestId, UUID schoolId, String name) {}

  record ChannelUpdate(String name, Boolean active, Long expectedVersion) {}

  record ChannelAddressUpdate(String address, Long expectedVersion) {}

  ChannelQueries.ChannelAddress updateAddress(UUID id, ChannelAddressUpdate command);

  Channel create(ChannelCreate command);

  Channel update(UUID id, ChannelUpdate command);
}
