package org.teraschool.admissions.api;

import java.util.UUID;

/** 学校招生渠道；本批只管理名称和启停状态。 */
public record Channel(UUID id, UUID schoolId, String name, boolean active, long version) {}
