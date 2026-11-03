package org.teraschool.admissions.api;

import java.util.List;
import java.util.UUID;

public interface ChannelQueries {
  record ChannelSchoolOption(UUID id, String name, boolean maintainable) {}

  record ChannelCapabilities(List<ChannelSchoolOption> schools) {}

  record ChannelPage(List<Channel> items, int page, int size, boolean hasMore) {}

  ChannelCapabilities capabilities();

  ChannelPage list(UUID schoolId, int page, int size);
}
